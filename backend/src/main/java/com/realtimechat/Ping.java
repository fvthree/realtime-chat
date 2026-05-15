package com.realtimechat;

public record Ping(String roomId, String senderId, long clientPingTs) implements ChatEvent {}
