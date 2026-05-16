package com.realtimechat;

public record Presence(String roomId, int connected) implements ChatEvent {}
