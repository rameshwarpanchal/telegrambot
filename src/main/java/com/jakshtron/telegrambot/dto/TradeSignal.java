package com.jakshtron.telegrambot.dto;

import java.util.List;

public record TradeSignal(
    String asset,         // NIFTY, BANKNIFTY, SENSEX, etc.
    String expiry,        // e.g., "07 JULY"
    Integer strikePrice,  // e.g., 24250
    String optionType,    // CE or PE
    String entryType,     // ABOVE, BELOW, MARKET
    Double entryPrice,    // e.g., 155.0
    List<Double> targets, // Handles arrays like [165.0, 180.0, 200.0]
    Double stopLoss       // e.g., 140.0
) {}