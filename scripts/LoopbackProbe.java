import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Selector;

/** Diagnostic only; binds an ephemeral loopback socket and exchanges no user data. */
class LoopbackProbe {
    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             Socket client = new Socket(loopback, server.getLocalPort());
             Socket accepted = server.accept()) {
            System.out.println("PASS loopback=" + loopback + " localPort=" + server.getLocalPort());
        }
        try (Selector selector = Selector.open()) {
            System.out.println("PASS selector=" + selector.getClass().getName());
        }
    }
}
