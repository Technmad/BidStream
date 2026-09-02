import { beforeEach, describe, expect, it } from "vitest";
import { nextConnectionState, useConnectionStore } from "./connectionStore";

describe("nextConnectionState (§9 FSM) — pure transition table", () => {
  it("connecting -> live on connect_success", () => {
    expect(nextConnectionState("connecting", "connect_success")).toBe("live");
  });

  it("connecting -> reconnecting on connect_failed", () => {
    expect(nextConnectionState("connecting", "connect_failed")).toBe("reconnecting");
  });

  it("live -> reconnecting on socket_closed", () => {
    expect(nextConnectionState("live", "socket_closed")).toBe("reconnecting");
  });

  it("reconnecting -> connecting on backoff_elapsed", () => {
    expect(nextConnectionState("reconnecting", "backoff_elapsed")).toBe("connecting");
  });

  it("live -> stale on tab_hidden_timeout", () => {
    expect(nextConnectionState("live", "tab_hidden_timeout")).toBe("stale");
  });

  it("stale -> live on tab_visible_resynced", () => {
    expect(nextConnectionState("stale", "tab_visible_resynced")).toBe("live");
  });

  it("stale -> reconnecting on tab_visible_resync_failed", () => {
    expect(nextConnectionState("stale", "tab_visible_resync_failed")).toBe("reconnecting");
  });

  it("ignores events that don't apply to the current state (no-op, not a crash)", () => {
    expect(nextConnectionState("live", "connect_success")).toBe("live");
    expect(nextConnectionState("connecting", "tab_hidden_timeout")).toBe("connecting");
    expect(nextConnectionState("stale", "socket_closed")).toBe("stale");
  });
});

describe("useConnectionStore", () => {
  beforeEach(() => {
    useConnectionStore.getState().reset();
  });

  it("starts in connecting", () => {
    expect(useConnectionStore.getState().status).toBe("connecting");
  });

  it("dispatch drives the same transitions as the pure function, and records lastLiveAt on reaching live", () => {
    const { dispatch } = useConnectionStore.getState();
    expect(useConnectionStore.getState().lastLiveAt).toBeNull();
    dispatch("connect_success");
    expect(useConnectionStore.getState().status).toBe("live");
    expect(useConnectionStore.getState().lastLiveAt).not.toBeNull();
  });

  it("a full drop-and-recover cycle walks live -> reconnecting -> connecting -> live", () => {
    const { dispatch } = useConnectionStore.getState();
    dispatch("connect_success");
    expect(useConnectionStore.getState().status).toBe("live");
    dispatch("socket_closed");
    expect(useConnectionStore.getState().status).toBe("reconnecting");
    dispatch("backoff_elapsed");
    expect(useConnectionStore.getState().status).toBe("connecting");
    dispatch("connect_success");
    expect(useConnectionStore.getState().status).toBe("live");
  });
});
