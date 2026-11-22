# HTTP Client and Concurrent Server

Network Systems midterm project (Group M6): a basic HTTP/1.1 client and a concurrent HTTP server in Java.

```
client/HttpClient.java
server/HttpServer.java
server/HttpRequestHandler.java
report.pdf
```

Requires Java 11 or newer (tested with JDK 25).

## 1. Compile

From this folder:

```bash
javac -d build server/*.java client/HttpClient.java
```

## 2. Start the server

```bash
java -cp build HttpServer [port] [data_directory]
```

Both arguments are optional. The defaults are port `8080` and the folder `server_data`, which is created if it does not exist. For example, `java -cp build HttpServer 9090 mydata` uses port 9090 and the folder `mydata`.

Leave the server running and open a second terminal for the client. Stop the server with Ctrl+C.

## 3. Run the client

```bash
java -cp build HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [content]
```

`content` is required for POST and PUT. Put JSON in single quotes.

## Example

```bash
java -cp build HttpClient localhost 8080 POST   /users/dev.json '{"name":"Dev","age":21}'   
java -cp build HttpClient localhost 8080 GET    /users/dev.json                             
java -cp build HttpClient localhost 8080 PUT    /users/dev.json '{"name":"Dev","age":22}'   
java -cp build HttpClient localhost 8080 DELETE /users/dev.json                             
java -cp build HttpClient localhost 8080 GET    /users/dev.json                             
```

The path `/users/dev.json` is the file `server_data/users/dev.json`.

The server also works with curl:

```bash
curl -i -X POST --data-binary '{"name":"Dev"}' http://localhost:8080/users/dev.json
curl -i http://localhost:8080/users/dev.json
```

Five clients at the same time (concurrency test):

```bash
for i in 1 2 3 4 5; do java -cp build HttpClient localhost 8080 POST /concurrent/client$i.json "{\"client\":$i}" | head -1 & done; wait
```

The server prints one line per request, showing the thread that handled it.

## Details

How the program works, the status codes and the test results are in `report.pdf`.
