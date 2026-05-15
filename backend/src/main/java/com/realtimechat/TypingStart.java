package com.realtimechat;

public record TypingStart(String roomId, String senderId) implements ChatEvent {}
