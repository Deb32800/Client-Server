import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;


public class HttpServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_DATA_DIRECTORY = "server_data";

    public static void main(String[] args) {
        if (args.length > 2) {
            System.err.println("Usage: java HttpServer [port] [data_directory]");
            return;
        }

        final int port;
        try {
            port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("Port must be between 1 and 65535");
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + e.getMessage());
            return;
        }

        Path dataDirectory = Paths.get(
                args.length == 2 ? args[1] : DEFAULT_DATA_DIRECTORY)
                .toAbsolutePath().normalize();

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            System.err.println("Cannot prepare storage directory: " + e.getMessage());
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("HTTP server listening on port " + port);
            System.out.println("Storage directory: " + dataDirectory);

            int nextClientId = 1;
            while (true) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (IOException e) {
                    System.err.println("Could not accept connection: " + e.getMessage());
                    continue;
                }


                try {
                    Thread clientThread = new Thread(
                            new HttpRequestHandler(clientSocket, dataDirectory),
                            "http-client-" + nextClientId++);
                    clientThread.start();
                } catch (RuntimeException | Error e) {
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                    // Keep accepting other clients even if this one could not be handled.
                    System.err.println("Could not start client handler: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start HTTP server: " + e.getMessage());
        }
    }
}
