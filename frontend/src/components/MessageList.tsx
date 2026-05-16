import { useEffect, useRef } from "react";
import type { LocalMessage } from "../types";
import { MessageRow } from "./MessageRow";

export function MessageList({
  messages,
  typing,
  onRetry,
}: {
  messages: LocalMessage[];
  typing: Set<string>;
  onRetry: (tempId: string) => void;
}) {
  const scrollRef = useRef<HTMLOListElement>(null);

  // Stick to bottom when new messages arrive (Stage 1: always; Stage 7 may
  // add "you scrolled up, hold position" behavior).
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [messages.length, typing.size]);

  if (messages.length === 0) {
    return (
      <div className="message-list__empty" role="status">
        <div>No messages yet. Type below to start.</div>
        <div className="message-list__empty-arrow" aria-hidden="true">↓</div>
      </div>
    );
  }

  const typingList = [...typing];

  return (
    <ol
      ref={scrollRef}
      className="message-list"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
    >
      {messages.map((m) => (
        <MessageRow key={m.id} msg={m} onRetry={onRetry} />
      ))}
      {typingList.length > 0 && (
        <li className="typing-strip" aria-live="polite">
          {typingList.length === 1
            ? `${typingList[0]} is typing`
            : `${typingList.length} people typing`}
        </li>
      )}
    </ol>
  );
}
