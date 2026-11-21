# HTTP Client and Concurrent Server

Network Systems midterm project: a basic HTTP/1.1 client and a concurrent HTTP server in Java, using only `Socket` and `ServerSocket`. The server stores resources as files and supports GET, POST, PUT and DELETE. Each TCP connection carries one request and one response.

## Structure

```
README.md
client/
    HttpClient.java            client (Part B)
server/
    HttpServer.java            accepts connections, one thread per client (A1, A2)
    HttpRequestHandler.java    parses one request, does the file operation, sends the response (A3-A5)
```

## Compile

From the project folder:

```bash
javac -d build server/*.java client/HttpClient.java
```

The `.class` files go into `build/`, which is not committed.

## Server

```bash
java -cp build HttpServer [port] [data_directory]
```

| Argument | Default | Meaning |
|---|---|---|
| `port` | `8080` | TCP port to listen on (1-65535) |
| `data_directory` | `server_data` | Folder where resources are stored; created if missing |

The request path maps to a file in the data directory: `/users/alice.json` is `server_data/users/alice.json`.

| Request | Result |
|---|---|
| GET, file exists | `200 OK` with the file content |
| POST, file missing | `201 Created`; parent folders are created |
| POST, file exists | `409 Conflict` |
| PUT, file exists | `204 No Content`; content replaced |
| DELETE, file exists | `204 No Content`; file deleted |
| GET / PUT / DELETE, file missing | `404 Not Found` |
| Any other method | `405 Method Not Allowed` (with an `Allow` header) |
| Malformed request, invalid `Content-Length`, body over 10 MB, path outside the data directory, a directory as target, or a parent path that is a file | `400 Bad Request` |
| No data from the client for 10 seconds | `408 Request Timeout` |
| Unexpected error | `500 Internal Server Error` |

Every response has `Content-Length` (`0` when there is no body) and `Connection: close`. Each accepted connection is handled in a new thread, and file operations are synchronized on one shared lock, so concurrent clients cannot corrupt the storage.

## Client

```bash
java -cp build HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [content]
```

- `method` can be lowercase too; it is sent in uppercase.
- `path` must start with `/`.
- `content` is required for POST and PUT and not allowed for GET and DELETE. Put JSON in single quotes so the shell leaves it alone.

The client builds the request itself (request line, `Host`, `Content-Type: application/json` and `Content-Length` in bytes when there is a body, `Connection: close`), then prints the status code and reason phrase, the headers, and the body read using `Content-Length`. Connection errors (unknown host, refused connection, 10-second read timeout) are reported with a one-line message.

## Example

Terminal 1:

```bash
java -cp build HttpServer
```

Terminal 2:

```bash
java -cp build HttpClient localhost 8080 POST   /users/alice.json '{"name":"Alice","age":21,"major":"CS"}'   # 201 Created
java -cp build HttpClient localhost 8080 GET    /users/alice.json                                           # 200 OK + content
java -cp build HttpClient localhost 8080 PUT    /users/alice.json '{"name":"Alice","age":22,"major":"CS"}'   # 204 No Content
java -cp build HttpClient localhost 8080 POST   /users/alice.json '{"name":"Alice"}'                         # 409 Conflict
java -cp build HttpClient localhost 8080 DELETE /users/alice.json                                           # 204 No Content
java -cp build HttpClient localhost 8080 GET    /users/alice.json                                           # 404 Not Found
```

Example output:

```
Status: 200 OK
Headers:
  Content-Type: application/json
  Content-Length: 38
  Connection: close
Body (38 bytes):
{"name":"Alice","age":21,"major":"CS"}
```

The server also works with curl:

```bash
curl -v http://localhost:8080/users/alice.json
curl -v -X POST -H 'Content-Type: application/json' --data-binary '{"name":"Alice","age":21}' http://localhost:8080/users/alice.json
curl -v -X PUT  -H 'Content-Type: application/json' --data-binary '{"name":"Alice","age":22}' http://localhost:8080/users/alice.json
curl -v -X DELETE http://localhost:8080/users/alice.json
curl -v --path-as-is http://localhost:8080/../../secret.txt                                          # 400 Bad Request
```

Concurrency test, five clients at the same time, each creating a different file:

```bash
for i in 1 2 3 4 5; do
  java -cp build HttpClient localhost 8080 POST /concurrent/client$i.json "{\"client\":$i}" | head -1 &
done; wait
ls server_data/concurrent
```

## Environment

Tested on macOS 14.4 with Oracle JDK 25.0.1, curl 8.4.0 and Python 3.14.7.

## Known limitations

- One global lock serialises all file operations, including GET.
- Request bodies are limited to 10 MB because the whole body is kept in memory.
- The 10-second timeout applies to each read, so a client that keeps sending a byte every few seconds can still hold its thread.
- Paths are not percent-decoded (`/a%20b.json` is a file named `a%20b.json`), and query strings are not removed.
- The client sends the path as typed, always uses `Content-Type: application/json`, and supports plain HTTP only.
