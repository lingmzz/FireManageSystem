package com.firemanage.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;

@Value
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreatedId {
    long id;
}
