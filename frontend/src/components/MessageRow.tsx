import { memo } from "react";
import type { LocalMessage } from "../types";

function fmtTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export const MessageRow = memo(function MessageRow({
  msg,
  onRetry,
}: {
  msg: LocalMessage;
  onRetry: (tempId: string) => void;
}) {
  const showRetry = msg.status === "failed" && msg.tempId;

  return (
    <li className="message">
      <span className="message__sender">{msg.senderId}</span>
      <span className="message__ts mono">{fmtTime(msg.clientSendTs)}</span>
      <span />
      <span>
        {msg.status === "pending" && (
          <span className="message__status message__status--pending mono">sending</span>
        )}
        {msg.status === "failed" && (
          <span className="message__status message__status--failed mono">
            failed
            {showRetry && (
              <button
                type="button"
                className="message__retry"
                onClick={() => onRetry(msg.tempId!)}
                aria-label="Retry sending message"
              >
                retry
              </button>
            )}
          </span>
        )}
      </span>
      <div className="message__body">{msg.text}</div>
    </li>
  );
});
