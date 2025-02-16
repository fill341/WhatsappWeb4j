package it.auties.whatsapp.listener;

import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.Store;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.socket.Socket;

public interface OnWhatsappChats extends Listener {
    /**
     * Called when {@link Socket} receives all the chats from WhatsappWeb's WebSocket.
     * When this event is fired, it is guaranteed that all metadata excluding messages will be present.
     * To access this data use {@link Store#chats()}.
     * If you also need the messages to be loaded, please refer to {@link OnChatMessages#onChatMessages(Chat, boolean)}.
     * Particularly old chats may come later through {@link Listener#onChatMessages(Chat, boolean)}
     *
     * @param whatsapp an instance to the calling api
     */
    @Override
    void onChats(Whatsapp whatsapp);
}