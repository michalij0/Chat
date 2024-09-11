import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

public class ChatServer {
    private static Map<SocketChannel, String> clientIdentifiers = new HashMap<>();
    private static Map<String, String> ipToIdentifier = new HashMap<>();

    private static final String COLOR_GREEN = "\u001B[32m";
    private static final String COLOR_ORANGE = "\u001B[33m";
    private static final String COLOR_RED = "\u001B[31m";
    private static final String COLOR_RESET = "\u001B[0m";

    public static void main(String[] args) {
        try {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(8888));
            serverChannel.configureBlocking(false);
            Selector selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Serwer czatu uruchomiony na porcie 8888.");

            Thread consoleThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    String command = scanner.nextLine();
                    if (command.equalsIgnoreCase("list")) {
                        listClients();
                    } else if (command.startsWith("kick ")) {
                        String uid = command.substring(5).trim();
                        kickClient(uid, selector);
                    } else if (command.startsWith("say ")) {
                        String message = command.substring(4).trim();
                        serverBroadcastMessage(selector, message);
                    }
                }
            });

            consoleThread.start();

            while (true) {
                int readyChannels = selector.select();
                if (readyChannels == 0) continue;
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isAcceptable()) {
                        acceptClient(selector, serverChannel);
                    }
                    if (key.isReadable()) {
                        handleClientInput(key, selector);
                    }
                    keyIterator.remove();
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private static void acceptClient(Selector selector, ServerSocketChannel serverChannel)
            throws IOException, NoSuchAlgorithmException {
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(selector, SelectionKey.OP_READ);
        String ip = clientChannel.getRemoteAddress().toString();
        String identifier = generateRandomIdentifier(ip);
        clientIdentifiers.put(clientChannel, identifier);
        ipToIdentifier.put(ip, identifier);
        System.out.println("Nowy klient dołączył: " + identifier + " (IP: " + ip + ")");

        // Wiadomości w osobnych liniach
        sendWelcomeMessage(clientChannel, identifier);
        broadcastMessage(selector, clientChannel,
                COLOR_GREEN + identifier + " dołączył do czatu." + COLOR_RESET, null);
    }

    private static void handleClientInput(SelectionKey key, Selector selector) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead = clientChannel.read(buffer);

        if (bytesRead == -1) {
            // Klient zamknął połączenie
            disconnectClient(clientChannel, selector);
        } else if (bytesRead == 0) {
            // Nic do odczytania - zignoruj
        } else {
            buffer.flip();
            byte[] messageBytes = new byte[buffer.remaining()];
            buffer.get(messageBytes);
            String message = new String(messageBytes).trim();
            String identifier = clientIdentifiers.get(clientChannel);
            System.out.println("Klient (" + identifier + "): " + message);
            // Informacja zwrotna do nadawcy
            sendDirectMessage(clientChannel, "Me: " + message);

            // Przekaż wiadomość do innych klientów
            broadcastMessage(selector, clientChannel, identifier + ": " + message, identifier);
        }
    }

    private static void disconnectClient(SocketChannel clientChannel, Selector selector) throws IOException {
        String identifier = clientIdentifiers.remove(clientChannel);
        ipToIdentifier.values().remove(identifier);
        System.out.println("Klient opuścił czat: " + identifier);
        clientChannel.close();
        broadcastMessage(selector, clientChannel, COLOR_ORANGE + identifier + " opuścił czat." + COLOR_RESET, null);
    }

    private static void sendWelcomeMessage(SocketChannel clientChannel, String clientId) throws IOException {
        // Wiadomość powitalna dla nowego klienta
        String welcomeMessage = "Pomyślnie połączono z chatem\nTwoje UID: " + clientId + "\n";
        ByteBuffer buffer = ByteBuffer.wrap(welcomeMessage.getBytes());
        clientChannel.write(buffer);
    }

    private static void sendDirectMessage(SocketChannel clientChannel, String message) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
        clientChannel.write(buffer);
    }

    private static void broadcastMessage(Selector selector, SocketChannel sender, String message,
                                         String senderIdentifier) throws IOException {
        Set<SelectionKey> keys = selector.keys();
        for (SelectionKey key : keys) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                SocketChannel clientChannel = (SocketChannel) key.channel();
                if (senderIdentifier != null && clientChannel == sender) {
                    // Skip sending to the sender
                    continue;
                } else {
                    ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
                    clientChannel.write(buffer);
                }
            }
        }
    }

    private static void serverBroadcastMessage(Selector selector, String message) {
        String serverMessage = COLOR_RED + "Server: " + message + COLOR_RESET; // Dodanie ANSI kodu, aby wiadomość wyróżniała się w czerwonym kolorze
        Set<SelectionKey> keys = selector.keys();
        for (SelectionKey key : keys) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                SocketChannel clientChannel = (SocketChannel) key.channel();
                ByteBuffer buffer = ByteBuffer.wrap(serverMessage.getBytes());
                try {
                    clientChannel.write(buffer);
                } catch (IOException e) {
                    System.out.println("Błąd podczas wysyłania wiadomości do klienta: " + e.getMessage());
                }
            }
        }
    }

    private static String generateRandomIdentifier(String ip) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(ip.getBytes());
        Random random = new Random();

        StringBuilder identifier = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            identifier.append(String.format("%02x", hash[random.nextInt(hash.length)]));
        }
        return identifier.toString();
    }

    private static void listClients() {
        StringBuilder clientList = new StringBuilder("Lista klientów:\n");
        for (Map.Entry<SocketChannel, String> entry : clientIdentifiers.entrySet()) {
            SocketChannel channel = entry.getKey();
            String identifier = entry.getValue();
            try {
                String ip = channel.getRemoteAddress().toString();
                clientList.append(identifier).append(" (IP: ").append(ip).append(")\n");
            } catch (IOException e) {
                clientList.append(identifier).append(" (IP: błąd w odczycie IP)\n");
            }
        }
        System.out.print(clientList.toString());
    }

    private static void kickClient(String uid, Selector selector) {
        SocketChannel clientChannelToKick = null;
        for (Map.Entry<SocketChannel, String> entry : clientIdentifiers.entrySet()) {
            if (entry.getValue().equals(uid)) {
                clientChannelToKick = entry.getKey();
                break;
            }
        }
        if (clientChannelToKick != null) {
            try {
                System.out.println("Rozłączam klienta: " + uid);
                broadcastMessage(selector, clientChannelToKick, COLOR_RED + uid + " został wyrzucony z czatu." + COLOR_RESET, null);
                clientChannelToKick.close();
                clientIdentifiers.remove(clientChannelToKick);
            } catch (IOException e) {
                System.out.println("Błąd podczas rozłączania klienta " + uid + ": " + e.getMessage());
            }
        } else {
            System.out.println("Nie znaleziono klienta o UID: " + uid);
        }
    }
}