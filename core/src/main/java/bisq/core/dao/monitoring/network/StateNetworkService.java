/*
 * This file is part of Bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.monitoring.network;

import bisq.core.dao.monitoring.model.StateHash;
import bisq.core.dao.monitoring.network.messages.GetStateHashesRequest;
import bisq.core.dao.monitoring.network.messages.GetStateHashesResponse;
import bisq.core.dao.monitoring.network.messages.NewStateHashMessage;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.MessageListener;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.peers.Broadcaster;
import bisq.network.p2p.peers.PeerManager;

import bisq.common.UserThread;
import bisq.common.proto.network.NetworkEnvelope;

import javax.inject.Inject;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

@Slf4j
public abstract class StateNetworkService<Msg extends NewStateHashMessage<StH>,
        Req extends GetStateHashesRequest,
        Res extends GetStateHashesResponse<StH>,
        Han extends RequestStateHashesHandler<Req, Res>,
        StH extends StateHash> implements MessageListener {

    public interface Listener<Msg extends NewStateHashMessage<StH>, Req extends GetStateHashesRequest, StH extends StateHash> {
        void onNewStateHashMessage(Msg newStateHashMessage, Connection connection);

        void onGetStateHashRequest(Connection connection, Req getStateHashRequest);

        void onPeersStateHashes(List<StH> stateHashes, Optional<NodeAddress> peersNodeAddress);
    }

    public interface ResponseListener {
        void onSuccess(int serializedSize);

        void onFault();
    }

    protected final NetworkNode networkNode;
    protected final PeerManager peerManager;
    private final Broadcaster broadcaster;

    @Getter
    private final Map<NodeAddress, Han> requestStateHashHandlerMap = new HashMap<>();
    private final List<Listener<Msg, Req, StH>> listeners = new CopyOnWriteArrayList<>();
    private boolean messageListenerAdded;
    private final List<ResponseListener> responseListeners = new CopyOnWriteArrayList<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public StateNetworkService(NetworkNode networkNode,
                               PeerManager peerManager,
                               Broadcaster broadcaster) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.broadcaster = broadcaster;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected abstract Req castToGetStateHashRequest(NetworkEnvelope networkEnvelope);


    protected abstract boolean isGetStateHashesRequest(NetworkEnvelope networkEnvelope);


    protected abstract Msg castToNewStateHashMessage(NetworkEnvelope networkEnvelope);


    protected abstract boolean isNewStateHashMessage(NetworkEnvelope networkEnvelope);

    protected abstract Res getGetStateHashesResponse(int nonce, List<StH> stateHashes);

    protected abstract Msg getNewStateHashMessage(StH myStateHash);

    protected abstract Han getRequestStateHashesHandler(NodeAddress nodeAddress,
                                                        RequestStateHashesHandler.Listener<Res> listener);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (isNewStateHashMessage(networkEnvelope)) {
            Msg newStateHashMessage = castToNewStateHashMessage(networkEnvelope);
            log.debug("We received a {} from peer {} with stateHash={} ",
                    newStateHashMessage.getClass().getSimpleName(),
                    connection.getPeersNodeAddressOptional(),
                    newStateHashMessage.getStateHash());
            listeners.forEach(e -> e.onNewStateHashMessage(newStateHashMessage, connection));
        } else if (isGetStateHashesRequest(networkEnvelope)) {
            Req getStateHashRequest = castToGetStateHashRequest(networkEnvelope);
            log.debug("We received a {} from peer {} for height={} ",
                    getStateHashRequest.getClass().getSimpleName(),
                    connection.getPeersNodeAddressOptional(),
                    getStateHashRequest.getHeight());
            listeners.forEach(e -> e.onGetStateHashRequest(connection, getStateHashRequest));
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addListeners() {
        if (!messageListenerAdded) {
            networkNode.addMessageListener(this);
            messageListenerAdded = true;
        }
    }

    public void sendGetStateHashesResponse(Connection connection, int nonce, List<StH> stateHashes) {
        Res getStateHashesResponse = getGetStateHashesResponse(nonce, stateHashes);
        log.info("Send {} with {} stateHashes to peer {}", getStateHashesResponse.getClass().getSimpleName(),
                stateHashes.size(), connection.getPeersNodeAddressOptional());
        SettableFuture<Connection> future = networkNode.sendMessage(connection, getStateHashesResponse);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(Connection connection) {
                UserThread.execute(() -> responseListeners.forEach(listeners -> listeners.onSuccess(getStateHashesResponse.toProtoMessage().getSerializedSize()))
                );
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> responseListeners.forEach(StateNetworkService.ResponseListener::onFault)
                );
            }
        }, MoreExecutors.directExecutor());
    }

    public void requestHashesFromAllConnectedSeedNodes(int fromHeight) {
        networkNode.getConfirmedConnections().stream()
                .filter(peerManager::isSeedNode)
                .forEach(connection -> connection.getPeersNodeAddressOptional()
                        .ifPresent(e -> requestHashesFromSeedNode(fromHeight, e)));
    }

    public void broadcastMyStateHash(StH myStateHash) {
        broadcaster.broadcast(getNewStateHashMessage(myStateHash), networkNode.getNodeAddress());
    }

    public void requestHashes(int fromHeight, String peersAddress) {
        requestHashesFromSeedNode(fromHeight, new NodeAddress(peersAddress));
    }

    public void reset() {
        requestStateHashHandlerMap.clear();
    }

    public boolean isSeedNode(NodeAddress nodeAddress) {
        return peerManager.isSeedNode(nodeAddress);
    }

    public void addResponseListener(ResponseListener responseListener) {
        responseListeners.add(responseListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addListener(Listener<Msg, Req, StH> listener) {
        listeners.add(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void requestHashesFromSeedNode(int fromHeight, NodeAddress nodeAddress) {
        RequestStateHashesHandler.Listener<Res> listener = new RequestStateHashesHandler.Listener<>() {
            @Override
            public void onComplete(Res getStateHashesResponse, Optional<NodeAddress> peersNodeAddress) {
                requestStateHashHandlerMap.remove(nodeAddress);
                List<StH> stateHashes = getStateHashesResponse.getStateHashes();
                listeners.forEach(e -> e.onPeersStateHashes(stateHashes, peersNodeAddress));
            }

            @Override
            public void onFault(String errorMessage, @Nullable Connection connection) {
                log.warn("requestDaoStateHashesHandler with outbound connection failed.\n\tnodeAddress={}\n\t" +
                        "ErrorMessage={}", nodeAddress, errorMessage);
                requestStateHashHandlerMap.remove(nodeAddress);
            }
        };
        Han requestStateHashesHandler = getRequestStateHashesHandler(nodeAddress, listener);
        requestStateHashHandlerMap.put(nodeAddress, requestStateHashesHandler);
        requestStateHashesHandler.requestStateHashes(fromHeight);
    }
}
