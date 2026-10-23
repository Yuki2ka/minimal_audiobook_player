package io.github.yuki2ka.audiobookplayer;

import android.app.Application;

import io.github.yuki2ka.audiobookplayer.data.Db;
import io.github.yuki2ka.audiobookplayer.playback.PlayerEngine;
import io.github.yuki2ka.audiobookplayer.scan.Library;

/**
 * Process-wide singletons. Keeping the player, the library and the database at
 * application level means an orientation change does not restart playback or
 * rescan the folder — no service, no retained fragment, no duplicated state.
 */
public final class App extends Application {

    private static App instance;

    private Db db;
    private Library library;
    private PlayerEngine player;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        db = new Db(this);
        library = new Library(this, db);
        player = new PlayerEngine(this);
    }

    public static App get() {
        return instance;
    }

    public Db db() {
        return db;
    }

    public Library library() {
        return library;
    }

    public PlayerEngine player() {
        return player;
    }
}
