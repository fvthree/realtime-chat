package com.realtimechat;

/**
 * First frame the client sends after connecting, announcing its senderId.
 * Server responds with current Presence count.
 */
public record Hello(String roomId, String senderId) implements ChatEvent {}
