import { useEffect, useRef, useState } from "react";

export function Composer({
  roomId,
  disabled,
  onSend,
  onTypingStart,
  onTypingStop,
}: {
  roomId: string;
  disabled: boolean;
  onSend: (text: string) => void;
  onTypingStart: () => void;
  onTypingStop: () => void;
}) {
  const [value, setValue] = useState("");
  const isTypingRef = useRef(false);
  const stopTimerRef = useRef<number | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Re-focus when re-enabled so the user can keep typing after a reconnect.
  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  function commitTypingStop() {
    if (!isTypingRef.current) return;
    isTypingRef.current = false;
    onTypingStop();
  }

  function handleChange(next: string) {
    setValue(next);
    if (disabled) return;
    if (next.length > 0 && !isTypingRef.current) {
      isTypingRef.current = true;
      onTypingStart();
    }
    if (stopTimerRef.current !== null) clearTimeout(stopTimerRef.current);
    stopTimerRef.current = window.setTimeout(() => {
      commitTypingStop();
    }, 3000);
    if (next.length === 0) commitTypingStop();
  }

  function handleSubmit() {
    const text = value.trim();
    if (!text || disabled) return;
    onSend(text);
    setValue("");
    commitTypingStop();
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    } else if (e.key === "Escape") {
      inputRef.current?.blur();
    }
  }

  return (
    <form
      className="composer"
      onSubmit={(e) => {
        e.preventDefault();
        handleSubmit();
      }}
    >
      <input
        ref={inputRef}
        type="text"
        className="composer__input"
        placeholder={`Message #${roomId}…`}
        value={value}
        disabled={disabled}
        onChange={(e) => handleChange(e.target.value)}
        onKeyDown={handleKey}
        aria-label={`Message #${roomId}`}
        autoComplete="off"
        autoFocus
      />
      <button
        type="submit"
        className="composer__send"
        disabled={disabled || value.trim().length === 0}
        aria-label="Send message"
      >
        ⏎ send
      </button>
    </form>
  );
}
