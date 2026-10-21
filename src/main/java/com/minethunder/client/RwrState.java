package com.minethunder.client;

import com.minethunder.radar.RwrContact;
import com.minethunder.radar.RwrThreat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 客户端 RWR 接触缓存。 */
public final class RwrState {
    private RwrState() {}

    private static final List<RwrContact> CONTACTS = new ArrayList<>();

    public static void setContacts(List<RwrContact> contacts) {
        CONTACTS.clear();
        CONTACTS.addAll(contacts);
        CONTACTS.sort(Comparator.comparingInt((RwrContact c) -> -c.threat().ordinal()));
    }

    public static List<RwrContact> contacts() {
        return CONTACTS;
    }

    public static boolean hasTrack() {
        return CONTACTS.stream().anyMatch(c -> c.threat() == RwrThreat.TRACK);
    }

    public static boolean hasLock() {
        return CONTACTS.stream().anyMatch(c -> c.threat() == RwrThreat.LOCK);
    }

    public static boolean hasMissile() {
        return CONTACTS.stream().anyMatch(c -> c.threat() == RwrThreat.MISSILE);
    }

    public static void clear() {
        CONTACTS.clear();
    }
}
