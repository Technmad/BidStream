import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/src/api/client";
import type { PendingBid } from "@/src/state/pendingBids";
import { BidForm, type BidFormProps } from "./BidForm";

// §14: "driven by mocking the reconciliation hook's output directly rather than
// a real connection" — here that means BidForm's own inputs (props) plus the
// two side-effecting calls it makes (`placeBid`, `submitPendingBid`) are mocked;
// no real WS/REST/Zustand involved.
vi.mock("@/src/api/auctions", () => ({
  placeBid: vi.fn(),
}));
vi.mock("@/src/state/pendingBids", () => ({
  submitPendingBid: vi.fn(),
}));

import { placeBid } from "@/src/api/auctions";
import { submitPendingBid } from "@/src/state/pendingBids";

const placeBidMock = vi.mocked(placeBid);
const submitPendingBidMock = vi.mocked(submitPendingBid);

function baseProps(overrides: Partial<BidFormProps> = {}): BidFormProps {
  return {
    auctionId: "a1",
    confirmedPrice: "100.00",
    minIncrement: "5.00",
    myPendingBid: null,
    pendingBidStatus: "none",
    lastBidResult: null,
    isEnded: false,
    isAuthenticated: true,
    accessToken: "token-123",
    ...overrides,
  };
}

describe("BidForm — the bid state machine (§8, §14)", () => {
  beforeEach(() => {
    placeBidMock.mockReset();
    submitPendingBidMock.mockReset();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("prompts to log in when not authenticated, and disables nothing else to click", () => {
    render(<BidForm {...baseProps({ isAuthenticated: false, accessToken: null })} />);
    expect(screen.getByText(/log in/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /place bid/i })).not.toBeInTheDocument();
  });

  it("disables bidding and shows a closed message once the auction has ended", () => {
    render(<BidForm {...baseProps({ isEnded: true })} />);
    expect(screen.getByText(/auction has ended/i)).toBeInTheDocument();
  });

  it("prefills a suggested next bid from confirmedPrice + minIncrement", () => {
    render(<BidForm {...baseProps()} />);
    const input = screen.getByLabelText(/your bid/i) as HTMLInputElement;
    expect(input.value).toBe("105.00");
  });

  it("idle -> submitting: calls placeBid with the entered amount and the access token, then registers a pending bid via submitPendingBid", async () => {
    placeBidMock.mockResolvedValueOnce({ bidId: "b1", status: "PENDING", correlationId: "corr-1" });

    render(<BidForm {...baseProps()} />);
    fireEvent.click(screen.getByRole("button", { name: /place bid/i }));

    await vi.waitFor(() => expect(placeBidMock).toHaveBeenCalledWith("a1", "105.00", "token-123"));
    await vi.waitFor(() =>
      expect(submitPendingBidMock).toHaveBeenCalledWith(
        expect.objectContaining({ correlationId: "corr-1", auctionId: "a1", amount: "105.00" }),
      ),
    );
  });

  it("pending: renders a distinct 'confirming' status when pendingBidStatus is 'pending'", () => {
    const pending: PendingBid = { correlationId: "corr-1", auctionId: "a1", amount: "105.00", submittedAt: 0 };
    render(<BidForm {...baseProps({ pendingBidStatus: "pending", myPendingBid: pending })} />);
    expect(screen.getByRole("status")).toHaveTextContent(/confirming/i);
  });

  it("still-processing: after the §8.4 10s timeout, shows 'still processing' and explicitly does NOT call it a failure", () => {
    const pending: PendingBid = { correlationId: "corr-1", auctionId: "a1", amount: "105.00", submittedAt: 0 };
    render(<BidForm {...baseProps({ pendingBidStatus: "still-processing", myPendingBid: pending })} />);
    expect(screen.getByText(/still processing/i)).toBeInTheDocument();
    expect(screen.getByText(/not a failure/i)).toBeInTheDocument();
  });

  it("accepted: renders an accepted confirmation from lastBidResult", () => {
    render(
      <BidForm
        {...baseProps({ lastBidResult: { correlationId: "corr-1", result: { outcome: "ACCEPTED" } } })}
      />,
    );
    expect(screen.getByText(/bid was accepted/i)).toBeInTheDocument();
  });

  it("rejected: renders the rejection reason inline from lastBidResult", () => {
    render(
      <BidForm
        {...baseProps({
          lastBidResult: {
            correlationId: "corr-1",
            result: { outcome: "REJECTED", reason: "BELOW_MIN_INCREMENT" },
          },
        })}
      />,
    );
    expect(screen.getByText(/rejected/i)).toBeInTheDocument();
    expect(screen.getByText(/BELOW_MIN_INCREMENT/)).toBeInTheDocument();
  });

  it("synchronous 409 from placeBid itself: shows a specific message built from reason/currentPrice/minIncrement, distinct from the async BID_RESULT rejection path", async () => {
    placeBidMock.mockRejectedValueOnce(
      new ApiError(
        { title: "Conflict", status: 409, reason: "BELOW_MIN_INCREMENT", currentPrice: "110.00", minIncrement: "5.00" },
        409,
      ),
    );

    render(<BidForm {...baseProps()} />);
    fireEvent.click(screen.getByRole("button", { name: /place bid/i }));

    await vi.waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/110\.00/));
    expect(submitPendingBidMock).not.toHaveBeenCalled();
  });

  it("synchronous 429: shows the generic slow-down message, no fabricated countdown", async () => {
    placeBidMock.mockRejectedValueOnce(new ApiError({ title: "Too Many Requests", status: 429, reason: "RATE_LIMITED" }, 429));

    render(<BidForm {...baseProps()} />);
    fireEvent.click(screen.getByRole("button", { name: /place bid/i }));

    await vi.waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/slow down/i));
  });

  it("synchronous 403: shows an inline message AND logs a console warning as a bug signal (§13)", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    placeBidMock.mockRejectedValueOnce(new ApiError({ title: "Forbidden", status: 403 }, 403));

    render(<BidForm {...baseProps()} />);
    fireEvent.click(screen.getByRole("button", { name: /place bid/i }));

    await vi.waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/not allowed/i));
    expect(warnSpy).toHaveBeenCalled();
  });
});
