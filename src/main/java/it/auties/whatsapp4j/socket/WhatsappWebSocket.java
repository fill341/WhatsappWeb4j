package it.auties.whatsapp4j.socket;

import it.auties.whatsapp4j.api.WhatsappState;
import it.auties.whatsapp4j.api.WhatsappConfiguration;
import it.auties.whatsapp4j.event.WhatsappListener;
import it.auties.whatsapp4j.model.*;
import it.auties.whatsapp4j.request.InitialRequest;
import it.auties.whatsapp4j.request.LogOutRequest;
import it.auties.whatsapp4j.request.SolveChallengeRequest;
import it.auties.whatsapp4j.request.TakeOverRequest;
import it.auties.whatsapp4j.response.Response;
import it.auties.whatsapp4j.utils.*;
import it.auties.whatsapp4j.utils.debug.ConsoleThread;
import jakarta.websocket.*;
import lombok.Getter;
import lombok.SneakyThrows;

import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static it.auties.whatsapp4j.utils.CypherUtils.*;

@ClientEndpoint(configurator = WebSocketConfiguration.class)
@Accessors(fluent = true)
public class WhatsappWebSocket {
  private Session session;
  private ScheduledExecutorService scheduler;
  private @Getter @NotNull WhatsappState state;
  private final @NotNull WhatsappListener listener;
  private final @NotNull WebSocketContainer webSocketContainer;
  private final @NotNull WhatsappManager whatsappManager;
  private final @NotNull WhatsappKeys whatsappKeys;
  private final @NotNull WhatsappConfiguration options;
  private final @NotNull WhatsappQRCode qrCode;
  private final @NotNull BinaryDecoder binaryDecoder;

  public WhatsappWebSocket(@NotNull WhatsappListener listener, @NotNull WhatsappConfiguration options, @NotNull WhatsappManager manager, @NotNull WhatsappKeys whatsappKeys) {
    this.whatsappKeys = whatsappKeys;
    this.whatsappManager = manager;
    this.options = options;
    this.listener = listener;
    this.qrCode = new WhatsappQRCode();
    this.state = WhatsappState.NOTHING;
    this.binaryDecoder = new BinaryDecoder();
    this.webSocketContainer = ContainerProvider.getWebSocketContainer();
    webSocketContainer.setDefaultMaxSessionIdleTimeout(Duration.ofDays(30).toMillis());
  }

  @SneakyThrows
  public void connect(){
    this.session = webSocketContainer.connectToServer(this, URI.create(options.whatsappUrl()));
    this.scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.scheduleAtFixedRate(this::sendPing, 0, 1, TimeUnit.MINUTES);
    scheduler.schedule(new ConsoleThread(whatsappManager), 0, TimeUnit.MILLISECONDS);
    listener.onConnecting();
  }

  @SneakyThrows
  public void disconnect(@Nullable String reason, boolean logout, boolean reconnect){
    Validate.ifTrue(logout, () -> session.getAsyncRemote().sendObject(new LogOutRequest().toJson(), __ -> whatsappKeys.resetKeys()));

    whatsappManager.clear();
    scheduler.shutdownNow();
    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
    listener.onClose();

    this.state = WhatsappState.NOTHING;
    Validate.ifTrue(reconnect, this::connect);
  }

  @OnOpen
  public void onOpen(@NotNull Session session) {
    final var login = new InitialRequest(whatsappKeys, options);
    session.getAsyncRemote().sendObject(login.toJson(), __ -> this.state = WhatsappState.SENT_INITIAL_MESSAGE);
    listener.onOpen();
  }

  @OnMessage
  public void onMessage(@NotNull String message) {
    switch (state){
      case SENT_INITIAL_MESSAGE -> {
        if(whatsappKeys.mayRestore()) {
          restoreSession();
        }else {
          generateQrCode(message);
        }
      }
      case SOLVE_CHALLENGE -> solveChallenge(message);
      case SENT_CHALLENGE -> handleChallengeResponse(message);
      case WAITING_FOR_LOGIN -> login(message);
      case LOGGED_IN -> handleMessage(message);
    }
  }

  @OnMessage
  public void onBinaryMessage(byte[] msg) throws GeneralSecurityException {
    Validate.isTrue(msg[0] != '!', "Server pong from whatsapp, why did this get through?");
    Validate.isTrue(state == WhatsappState.LOGGED_IN, "Not logged in, did whatsapp send us a binary message to early?");

    var binaryMessage = BytesArray.forArray(msg);
    var tagAndMessagePair = binaryMessage.indexOf(',').map(binaryMessage::split).orElseThrow();

    var messageTag  = tagAndMessagePair.getFirst().toString();
    var messageContent  = tagAndMessagePair.getSecond();

    var message = messageContent.slice(32);
    var hmacValidation = hmacSha256(message, Objects.requireNonNull(whatsappKeys.macKey()));
    Validate.isTrue(hmacValidation.equals(messageContent.cut(32)), "Cannot login: Hmac validation failed!");

    var decryptedMessage = aesDecrypt(message, Objects.requireNonNull(whatsappKeys.encKey()));
    var whatsappMessage = binaryDecoder.decodeDecryptedMessage(decryptedMessage);
    whatsappManager.digestWhatsappNode(whatsappMessage, listener);
  }

  @SneakyThrows
  private void generateQrCode(@NotNull String message){
    var res = Response.fromJson(message);

    var status = res.getInteger("status");
    Validate.isTrue(status != 429, "Out of attempts to scan the QR code");

    var ttl = res.getInteger("ttl");
    CompletableFuture.delayedExecutor(ttl, TimeUnit.MILLISECONDS).execute(() -> disconnect(null, false, true));

    qrCode.generateQRCodeImage(res.getNullableString("ref"), whatsappKeys.publicKey(), whatsappKeys.clientId()).open();
    this.state = WhatsappState.WAITING_FOR_LOGIN;
  }

  private void restoreSession(){
    final var login = new TakeOverRequest(whatsappKeys, options);
    session.getAsyncRemote().sendObject(login.toJson(), __ -> this.state = WhatsappState.SOLVE_CHALLENGE);
  }

  @SneakyThrows
  private void solveChallenge(@NotNull String message){
    var res = Response.fromJson(message);
    var status = res.getNullableInteger("status");
    if(status != null){
      if (status == 200 || status == 405) {
        this.state = WhatsappState.LOGGED_IN;
        return;
      }

      if (status == 401 || status == 403 || status == 409) {
        whatsappKeys.resetKeys();
        disconnect(null, false, true);
        return;
      }
    }

    var challenge = BytesArray.forBase64(res.getString("challenge"));
    var signedChallenge = hmacSha256(challenge, Objects.requireNonNull(whatsappKeys.macKey()));
    var solveChallengeRequest = new SolveChallengeRequest(signedChallenge, whatsappKeys, options);
    session.getAsyncRemote().sendObject(solveChallengeRequest.toJson(), __ -> this.state = WhatsappState.SENT_CHALLENGE);
  }

  @SneakyThrows
  private void handleChallengeResponse(@NotNull String message){
    var status = Response.fromJson(message).getNullableInteger("status");
    Validate.isTrue(status != null && status == 200, "Could not solve whatsapp challenge for server and client token renewal: %s".formatted(message));
    this.state = WhatsappState.LOGGED_IN;
  }

  @SneakyThrows
  private void login(@NotNull String message){
    var res = Response.fromJson(message);

    var base64Secret = res.getString("secret");
    var secret = BytesArray.forBase64(base64Secret);
    var pubKey = secret.cut(32);
    var sharedSecret = calculateSharedSecret(pubKey.data(), whatsappKeys.privateKey());
    var sharedSecretExpanded = hkdfExpand(sharedSecret, 80);

    var hmacValidation = hmacSha256(secret.cut(32).merged(secret.slice(64)), sharedSecretExpanded.slice(32, 64));
    Validate.isTrue(hmacValidation.equals(secret.slice(32, 64)), "Cannot login: Hmac validation failed!");

    var keysEncrypted = sharedSecretExpanded.slice(64).merged(secret.slice(64));
    var key = sharedSecretExpanded.cut(32);
    var keysDecrypted = aesDecrypt(keysEncrypted, key);

    whatsappKeys.initializeKeys(res.getString("serverToken"), res.getString("clientToken"), keysDecrypted.cut(32), keysDecrypted.slice(32, 64));
    this.state = WhatsappState.LOGGED_IN;
  }

  @SneakyThrows
  private void sendPing(){
    session.getAsyncRemote().sendPing(ByteBuffer.allocate(0));
  }

  @SneakyThrows
  private void handleMessage(@NotNull String message){
    var res = Response.fromJson(message);
    var type = res.getNullableString("type");
    var kind = res.getNullableString("kind");
    if (type == null || kind == null) {
      return;
    }

    disconnect(kind, false, options.reconnectWhenDisconnected().apply(kind));
  }
}