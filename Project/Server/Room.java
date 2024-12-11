package Project.Server;

import java.util.concurrent.ConcurrentHashMap;

import Project.Common.LoggerUtil;

import java.util.Random;

public class Room implements AutoCloseable{
    private String name;// unique name of the Room
    protected volatile boolean isRunning = false;
    private ConcurrentHashMap<Long, ServerThread> clientsInRoom = new ConcurrentHashMap<Long, ServerThread>();

    public final static String LOBBY = "lobby";

    private void info(String message) {
        LoggerUtil.INSTANCE.info(String.format("Room[%s]: %s", name, message));
    }

    public Room(String name) {
        this.name = name;
        isRunning = true;
        info("created");
    }

    public String getName() {
        return this.name;
    }

    protected synchronized void addClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to add a client that already exists in the room");
            return;
        }
        clientsInRoom.put(client.getClientId(), client);
        client.setCurrentRoom(this);

        // notify clients of someone joining
        sendRoomStatus(client.getClientId(), client.getClientName(), true);
        // sync room state to joiner
        syncRoomList(client);

        info(String.format("%s[%s] joined the Room[%s]", client.getClientName(), client.getClientId(), getName()));

    }

    protected synchronized void removedClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        // notify remaining clients of someone leaving
        // happen before removal so leaving client gets the data
        sendRoomStatus(client.getClientId(), client.getClientName(), false);
        clientsInRoom.remove(client.getClientId());
        LoggerUtil.INSTANCE.fine("Clients remaining in Room: " + clientsInRoom.size());

        info(String.format("%s[%s] left the room", client.getClientName(), client.getClientId(), getName()));

        autoCleanup();

    }

    /**
     * Takes a ServerThread and removes them from the Server
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param client
     */
    protected synchronized void disconnect(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        long id = client.getClientId();
        sendDisconnect(client);
        client.disconnect();
        // removedClient(client); // <-- use this just for normal room leaving
        clientsInRoom.remove(client.getClientId());
        LoggerUtil.INSTANCE.fine("Clients remaining in Room: " + clientsInRoom.size());
        
        // Improved logging with user data
        info(String.format("%s[%s] disconnected", client.getClientName(), id));
        autoCleanup();
    }

    protected synchronized void disconnectAll() {
        info("Disconnect All triggered");
        if (!isRunning) {
            return;
        }
        clientsInRoom.values().removeIf(client -> {
            disconnect(client);
            return true;
        });
        info("Disconnect All finished");
        autoCleanup();
    }

    /**
     * Attempts to close the room to free up resources if it's empty
     */
    private void autoCleanup() {
        if (!Room.LOBBY.equalsIgnoreCase(name) && clientsInRoom.isEmpty()) {
            close();
        }
    }

    public void close() {
        // attempt to gracefully close and migrate clients
        if (!clientsInRoom.isEmpty()) {
            sendMessage(null, "Room is shutting down, migrating to lobby");
            info(String.format("migrating %s clients", name, clientsInRoom.size()));
            clientsInRoom.values().removeIf(client -> {
                Server.INSTANCE.joinRoom(Room.LOBBY, client);
                return true;
            });
        }
        Server.INSTANCE.removeRoom(this);
        isRunning = false;
        clientsInRoom.clear();
        info(String.format("closed", name));
    }

    // send/sync data to client(s)

    /**
     * Sends to all clients details of a disconnect client
     * @param client
     */
    protected synchronized void sendDisconnect(ServerThread client) {
        info(String.format("sending disconnect status to %s recipients", clientsInRoom.size()));
        clientsInRoom.values().removeIf(clientInRoom -> {
            boolean failedToSend = !clientInRoom.sendDisconnect(client.getClientId(), client.getClientName());
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    }

    /**
     * Syncs info of existing users in room with the client
     * 
     * @param client
     */
    protected synchronized void syncRoomList(ServerThread client) {

        clientsInRoom.values().forEach(clientInRoom -> {
            if (clientInRoom.getClientId() != client.getClientId()) {
                client.sendClientSync(clientInRoom.getClientId(), clientInRoom.getClientName());
            }
        });
    }

    /**
     * Syncs room status of one client to all connected clients
     * 
     * @param clientId
     * @param clientName
     * @param isConnect
     */
    protected synchronized void sendRoomStatus(long clientId, String clientName, boolean isConnect) {
        info(String.format("sending room status to %s recipients", clientsInRoom.size()));
        clientsInRoom.values().removeIf(client -> {
            boolean failedToSend = !client.sendRoomAction(clientId, clientName, getName(), isConnect);
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    }

    /**
     * Sends a basic String message from the sender to all connectedClients
     * Internally calls processCommand and evaluates as necessary.
     * Note: Clients that fail to receive a message get removed from
     * connectedClients.
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param message
     * @param sender  ServerThread (client) sending the message or null if it's a
     *                server-generated message
     */
    protected synchronized void sendMessage(ServerThread sender, String message) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }

        // el286
        // 11/27/24
        String messageFormat = messageFormated(message);

        // Note: any desired changes to the message must be done before this section
        long senderId = sender == null ? ServerThread.DEFAULT_CLIENT_ID : sender.getClientId();

        // loop over clients and send out the message; remove client if message failed
        // to be sent
        // Note: this uses a lambda expression for each item in the values() collection,
        // it's one way we can safely remove items during iteration
        info(String.format("sending message to %s recipients: %s", clientsInRoom.size(), messageFormat));
        clientsInRoom.values().removeIf(client -> {
            // el286
            // 12/01/24
            if (client.isMuted(sender.getClientName())) {
                info(String.format("Message from %s skipped for %s due to mute.", sender.getClientName(), client.getClientName()));
                return false;
            }
            boolean failedToSend = !client.sendMessage(senderId, messageFormat);
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    }

    // el286
    // 11/27/24
    private String messageFormated(String message){
        message = message.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");

        message = message.replaceAll("\\*(.*?)\\*", "<i>$1</i>");

        message = message.replaceAll("_(.*?)_", "<u>$1</u>");

        message = message.replaceAll("#r (.*?) r#", "<span style='color:red;'>$1</span>");

        message = message.replaceAll("#b (.*?) b#", "<span style='color:blue;'>$1</span>");

        message = message.replaceAll("#g (.*?) g#", "<span style='color:green;'>$1</span>");

        return message;
    }
    // end send data to client(s)

    // receive data from ServerThread
    
    protected void handleCreateRoom(ServerThread sender, String room) {
        if (Server.INSTANCE.createRoom(room)) {
            Server.INSTANCE.joinRoom(room, sender);
        } else {
            sender.sendMessage(String.format("Room %s already exists", room));
        }
    }

    protected void handleJoinRoom(ServerThread sender, String room) {
        if (!Server.INSTANCE.joinRoom(room, sender)) {
            sender.sendMessage(String.format("Room %s doesn't exist", room));
        }
    }

    protected void handleListRooms(ServerThread sender, String roomQuery){
        sender.sendRooms(Server.INSTANCE.listRooms(roomQuery));
    }

    protected void clientDisconnect(ServerThread sender) {
        disconnect(sender);
    }

    // el286
    // 11/27/24
    protected void handleRollCommand(ServerThread sender, String rollCommand){
        try{
            if(rollCommand.contains("d")){
                String[] parts = rollCommand.split("d");
                int rolls = Integer.parseInt(parts[0]);
                int sides = Integer.parseInt(parts[1]);
                if(rolls <= 0 || sides <= 0){
                    sender.sendMessage("Roll command is invalid");
                    return;
                }
                Random random = new Random();
                String rollTotal = "";
                for (int i = 0; i < rolls; i++) {
                    rollTotal += random.nextInt(sides) + 1;
                    if (i < rolls - 1) {
                        rollTotal += ", ";
                    }
                }
                // el286
                // 12/01/24
                sendMessage(sender, String.format("<b><span style='color:blue;'>rolled %s and got %s</span></b>", rollCommand, rollTotal));
            } else {
                int max = Integer.parseInt(rollCommand);
                if(max <= 0){
                    sender.sendMessage("Roll command is invalid");
                    return;
                }
                int result = new Random().nextInt(max) + 1;
                // el286
                // 12/01/24
                sendMessage(sender, String.format("<b><span style='color:blue;'>rolled %s and got %d</span></b>", rollCommand, result)); 
            }
        } catch(NumberFormatException e){
            sender.sendMessage("Invalid roll");
        }
    }

    // el286
    // 11/27/24
    protected void handleFlipCommand(ServerThread sender){
        String[] results = {
            "heads",
            "tails"
        };

        String result = results[(int) (Math.random()*results.length)];
        // el286
        // 12/01/24
        sendMessage(sender, String.format("<i><span style='color:green;'>flipped a coin and got %s</span></b>", result));
    }

    // el286
    // 12/01/24
    protected synchronized void handlePrivateMessageById(ServerThread sender, long targetClientId, String message) {
        if (!isRunning) { 
            return;
        }

        ServerThread recipient = clientsInRoom.get(targetClientId);
    
        if (recipient == null) {
            sendMessage(sender, String.format("User with ID '%d' not found in the room.", targetClientId));
            return;
        }

        String formattedMessage = String.format("[Private from %s]: %s", sender.getClientName(), message);
        String formattedMessage2 = String.format("[Private to %s]: %s", recipient.getClientName(), message);

        info(String.format("Private message from %s to %s: %s", sender.getClientName(), recipient.getClientName(), message));

        boolean recipientMessageFailed = !recipient.sendMessage(sender.getClientId(), formattedMessage);
        if (recipientMessageFailed) {
            info(String.format("Failed to send private message to %s (ID: %d)", recipient.getClientName(), targetClientId));
            disconnect(recipient);
        }

        boolean senderMessageFailed = !sender.sendMessage(sender.getClientId(), formattedMessage2);
        if (senderMessageFailed) {
            info(String.format("Failed to confirm private message to sender %s (ID: %d)", sender.getClientName(), sender.getClientId()));
            disconnect(sender);
        }
    }

    // el286
    // 12/01/24
    protected synchronized void handleMute(ServerThread sender, long targetClientId) {
        ServerThread targetClient = clientsInRoom.get(targetClientId);

        if (targetClient == null) {
            sendMessage(sender,String.format("User with ID '%d' not found in the room.", targetClientId));
            return;
        }
        
        String formattedMessage = String.format("[You muted %s]", targetClient.getClientName());

        if (sender.addMutedClient(targetClient.getClientName())) {
            boolean senderMessageFailed = !sender.sendMessage(sender.getClientId(), formattedMessage);
            if (senderMessageFailed) {
                info(String.format("Failed to mute %s", targetClient.getClientName()));
            } else {
                info(String.format("%s muted %s", sender.getClientName(), targetClient.getClientName()));
            }   
        }
    }

    // el286
    // 12/01/24
    protected synchronized void handleUnmute(ServerThread sender, long targetClientId) {
        ServerThread targetClient = clientsInRoom.get(targetClientId);

        if (targetClient == null) {
            sendMessage(sender,String.format("User with ID '%d' not found in the room.", targetClientId));
            return;
        }

        String formattedMessage = String.format("[You unmuted %s]", targetClient.getClientName());

        if (sender.removeMutedClient(targetClient.getClientName())) {
            boolean senderMessageFailed = !sender.sendMessage(sender.getClientId(), formattedMessage);
            if (senderMessageFailed) {
                info(String.format("Failed to unmute %s", targetClient.getClientName()));
            } else {
                info(String.format("%s unmuted %s", sender.getClientName(), targetClient.getClientName()));
            }
        }
    }
    // end receive data from ServerThread
}
