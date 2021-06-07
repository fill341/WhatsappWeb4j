package it.auties.whatsapp4j.protobuf.message;

import com.fasterxml.jackson.annotation.*;
import java.util.*;
import lombok.*;
import lombok.experimental.Accessors;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public final class DeviceSyncMessage implements Message {
  @JsonProperty(value = "1")
  private byte[] serializedXmlBytes;
}
