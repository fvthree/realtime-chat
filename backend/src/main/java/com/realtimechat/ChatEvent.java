package com.realtimechat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Message.class, name = "msg"),
        @JsonSubTypes.Type(value = TypingStart.class, name = "typing_start"),
        @JsonSubTypes.Type(value = TypingStop.class, name = "typing_stop"),
        @JsonSubTypes.Type(value = Ping.class, name = "ping"),
        @JsonSubTypes.Type(value = Pong.class, name = "pong"),
        @JsonSubTypes.Type(value = Presence.class, name = "presence"),
        @JsonSubTypes.Type(value = Hello.class, name = "hello"),
})
public sealed interface ChatEvent
        permits Message, TypingStart, TypingStop, Ping, Pong, Presence, Hello {
    String roomId();
}
