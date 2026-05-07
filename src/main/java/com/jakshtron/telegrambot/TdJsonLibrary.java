package com.jakshtron.telegrambot;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface TdJsonLibrary extends Library {
    // Load the library (ensure tdjson.dll is in your project root or System32)
    TdJsonLibrary INSTANCE = Native.load("tdjson", TdJsonLibrary.class);

    // Core TDLib JSON functions
    Pointer td_json_client_create();
    void td_json_client_send(Pointer client, String request);
    String td_json_client_receive(Pointer client, double timeout);
    String td_json_client_execute(Pointer client, String request);
    void td_json_client_destroy(Pointer client);
}