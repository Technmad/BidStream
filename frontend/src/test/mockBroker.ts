/**
 * A lightweight in-memory mock STOMP broker (§14.3) for integration-testing the
 * connection manager without a real backend. `@stomp/stompjs` doesn't ship a
 * built-in in-memory broker, so this implements just enough of the STOMP wire
 * protocol (CONNECT/CONNECTED, SUBSCRIBE/UNSUBSCRIBE, MESSAGE, DISCONNECT) against
 * a fake `WebSocket`, handed to `ConnectionManager` via its test-only
 * `webSocketFactory` constructor argument.
 */

const NULL_BYTE = "\0";

type StompFrame = {
  command: string;
  headers: Record<string, string>;
  body: string;
};

function parseFrame(raw: string): StompFrame {
  const withoutNull = raw.endsWith(NULL_BYTE) ? raw.slice(0, -1) : raw;
  const [headerPart, ...bodyParts] = withoutNull.split("\n\n");
  const body = bodyParts.join("\n\n");
  const lines = headerPart.split("\n");
  const command = lines[0];
  const headers: Record<string, string> = {};
  for (const line of lines.slice(1)) {
    const idx = line.indexOf(":");
    if (idx === -1) continue;
    headers[line.slice(0, idx)] = line.slice(idx + 1);
  }
  return { command, headers, body };
}

function serializeFrame(command: string, headers: Record<string, string>, body = ""): string {
  const headerLines = Object.entries(headers)
    .map(([k, v]) => `${k}:${v}`)
    .join("\n");
  return `${command}\n${headerLines}\n\n${body}${NULL_BYTE}`;
}

type Listener = ((event: unknown) => void) | null;

/** A fake `WebSocket` good enough for `@stomp/stompjs`'s `Client`. */
class MockWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readyState = MockWebSocket.CONNECTING;
  onopen: Listener = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: Listener = null;
  onerror: Listener = null;
  protocol = "v12.stomp";

  constructor(private readonly broker: MockBroker) {
    broker.registerSocket(this);
    // Simulate the async nature of a real WebSocket handshake.
    queueMicrotask(() => {
      if (this.readyState !== MockWebSocket.CONNECTING) return;
      if (broker.nextConnectShouldFail) {
        broker.nextConnectShouldFail = false;
        this.readyState = MockWebSocket.CLOSED;
        this.onclose?.({});
        return;
      }
      this.readyState = MockWebSocket.OPEN;
      this.onopen?.({});
    });
  }

  send(data: string): void {
    this.broker.handleClientFrame(this, data);
  }

  close(): void {
    if (this.readyState === MockWebSocket.CLOSED) return;
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({});
  }

  /** Test-only: simulate the server dropping the connection. */
  simulateServerClose(): void {
    this.close();
  }

  deliver(raw: string): void {
    this.onmessage?.({ data: raw });
  }
}

type Subscription = { id: string; destination: string };

/**
 * The broker itself — tracks subscriptions per socket and lets tests publish
 * messages to a destination, simulate a disconnect, or force the next connect
 * attempt to fail (for reconnection-policy tests).
 */
export class MockBroker {
  private sockets = new Set<MockWebSocket>();
  private subscriptionsBySocket = new Map<MockWebSocket, Subscription[]>();
  nextConnectShouldFail = false;
  /** Every CONNECT frame's headers this broker has seen — lets tests assert on auth headers. */
  readonly connectFrames: Array<Record<string, string>> = [];

  webSocketFactory = (): WebSocket => new MockWebSocket(this) as unknown as WebSocket;

  registerSocket(socket: MockWebSocket): void {
    this.sockets.add(socket);
    this.subscriptionsBySocket.set(socket, []);
  }

  handleClientFrame(socket: MockWebSocket, raw: string): void {
    const frame = parseFrame(raw);
    switch (frame.command) {
      case "CONNECT":
      case "STOMP":
        this.connectFrames.push(frame.headers);
        socket.deliver(serializeFrame("CONNECTED", { version: "1.2", "heart-beat": "0,0" }));
        break;
      case "SUBSCRIBE": {
        const subs = this.subscriptionsBySocket.get(socket) ?? [];
        subs.push({ id: frame.headers.id, destination: frame.headers.destination });
        this.subscriptionsBySocket.set(socket, subs);
        break;
      }
      case "UNSUBSCRIBE": {
        const subs = this.subscriptionsBySocket.get(socket) ?? [];
        this.subscriptionsBySocket.set(
          socket,
          subs.filter((s) => s.id !== frame.headers.id),
        );
        break;
      }
      case "DISCONNECT":
        socket.close();
        break;
      default:
        break;
    }
  }

  /** Publishes a JSON payload to every active subscriber of `destination`, across every connected socket. */
  publish(destination: string, payload: unknown): void {
    for (const socket of this.sockets) {
      if (socket.readyState !== MockWebSocket.OPEN) continue;
      const subs = this.subscriptionsBySocket.get(socket) ?? [];
      for (const sub of subs) {
        if (sub.destination !== destination) continue;
        socket.deliver(
          serializeFrame(
            "MESSAGE",
            {
              subscription: sub.id,
              "message-id": Math.random().toString(36).slice(2),
              destination,
              "content-type": "application/json",
            },
            JSON.stringify(payload),
          ),
        );
      }
    }
  }

  /** Simulates the transport dropping — every currently-open socket is closed as if by a network error. */
  simulateDisconnectAll(): void {
    for (const socket of this.sockets) {
      if (socket.readyState === MockWebSocket.OPEN) socket.simulateServerClose();
    }
  }

  /** The next connection attempt's handshake fails outright (never reaches CONNECTED). */
  failNextConnect(): void {
    this.nextConnectShouldFail = true;
  }

  activeDestinations(): string[] {
    const all: string[] = [];
    for (const subs of this.subscriptionsBySocket.values()) {
      for (const s of subs) all.push(s.destination);
    }
    return all;
  }
}
