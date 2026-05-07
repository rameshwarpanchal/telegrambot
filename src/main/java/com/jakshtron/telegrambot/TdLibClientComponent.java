//package com.jakshtron.telegrambot;
//
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import org.drinkless.tdlib.Client;
//import org.drinkless.tdlib.TdApi;
//import org.springframework.stereotype.Component;
//
//@Component
//public class TdLibClientComponent {
//    private Client client = null;
//
//    @PostConstruct
//    public void init() {
//        System.out.println("Java Library Path: " + System.getProperty("java.library.path"));
//        try {
//            System.loadLibrary("zlib1");
//            System.loadLibrary("libcrypto-3-x64");
//            System.loadLibrary("libssl-3-x64");
//
//            // Load the main library
//            // If you have tdjni.dll, load that.
//            // If your JAR is designed for tdjson, use this:
////            System.loadLibrary("tdjson");
//            System.loadLibrary("tdjni");
//            System.out.println("Native library loaded successfully!");
//            client = Client.create(object -> {}, null, null);
//        } catch (UnsatisfiedLinkError e) {
//            System.err.println("CRITICAL: Could not find tdjni library!");
//            e.printStackTrace();
//            throw e; // Re-throw to stop Spring
//        }
//    }
//
//
//    public void send(TdApi.Function function, Client.ResultHandler handler) {
//        client.send(function, handler);
//    }
//
//    @PreDestroy
//    public void stop() {
//        if (client != null) {
//            // We send the Close function to TDLib
//            client.send(new TdApi.Close(), result -> {
//                System.out.println("TDLib client is closing...");
//            });
//        }
//    }
//}