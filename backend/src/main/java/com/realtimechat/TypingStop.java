package com.realtimechat;

public record TypingStop(String roomId, String senderId) implements ChatEvent {}
