package com.example.workflow.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/** Builds worker ids that stay unique across replicas on the same or different hosts. */
final class WorkerIds {

    private WorkerIds() {
    }

    static String generate() {
        return generate(hostname());
    }

    static String generate(String host) {
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // Container hostnames are the container id, which is unique per replica.
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
