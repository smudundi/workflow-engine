package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerIdsTests {

    @Test
    void prefixesTheHostnameAndAddsARandomSuffix() {
        String id = WorkerIds.generate("host-a");

        assertThat(id).startsWith("host-a-").hasSize("host-a-".length() + 8);
    }

    @Test
    void differentReplicasOnTheSameHostGetDifferentIds() {
        assertThat(WorkerIds.generate("host-a")).isNotEqualTo(WorkerIds.generate("host-a"));
    }
}
