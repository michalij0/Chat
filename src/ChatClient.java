import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class ChatClient {
    public static void main(String[] args) {
        try {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

            SocketChannel clientChannel = SocketChannel.open();
            clientChannel.connect(new InetSocketAddress("localhost", 8888));
            clientChannel.configureBlocking(false);

            Selector selector = Selector.open();
            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            Thread inputThread = new Thread(() -> {
                try {
                    while (true) {
                        String message = console.readLine();
                        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
                        clientChannel.write(buffer);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            inputThread.start();

            while (true) {
                int readyChannels = selector.select();
                if (readyChannels == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();

                    if (key.isReadable()) {
                        handleServerMessage(key);
                    }

                    keyIterator.remove();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleServerMessage(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead = clientChannel.read(buffer);

        if (bytesRead == -1) {
            // Serwer zamknął połączenie
            System.out.println("Serwer zakończył czat.");
            clientChannel.close();
            key.cancel();
            System.exit(0);
        } else {
            buffer.flip();
            byte[] messageBytes = new byte[buffer.remaining()];
            buffer.get(messageBytes);
            String message = new String(messageBytes);
            System.out.println(message);
        }
    }
}