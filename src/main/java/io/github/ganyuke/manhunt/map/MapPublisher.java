package io.github.ganyuke.manhunt.map;

import io.github.ganyuke.manhunt.game.MatchSession;

import java.nio.file.Path;

public interface MapPublisher {
    void publishSession(MatchSession session, Path sessionDirectory);
}
