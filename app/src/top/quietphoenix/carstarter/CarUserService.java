package top.quietphoenix.carstarter;

import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class CarUserService extends ICarService.Stub {
    private Thread daemonThread;
    private volatile String lastError = "";

    public CarUserService() {}

    private boolean online() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 7878), 1000);
            socket.setSoTimeout(1500);
            new PrintStream(socket.getOutputStream(), true).println("PING");
            String response = new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
            return response != null && response.startsWith("PONG");
        } catch (Exception e) { return false; }
    }

    public synchronized String ensureDaemon() {
        if (online()) return "OK existing daemon uid=" + android.os.Process.myUid();
        if (daemonThread == null || !daemonThread.isAlive()) {
            daemonThread = new Thread(() -> {
                try { top.quietphoenix.card.CarD.main(new String[0]); }
                catch (Throwable e) {
                    lastError = e.toString();
                    android.util.Log.e("CarStarter", "Daemon stopped", e);
                }
            }, "car-daemon");
            daemonThread.start();
        }
        for (int i = 0; i < 15; i++) {
            if (online()) return "OK Shizuku daemon uid=" + android.os.Process.myUid();
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return "ERR " + lastError;
    }

    public void destroy() { System.exit(0); }
}
