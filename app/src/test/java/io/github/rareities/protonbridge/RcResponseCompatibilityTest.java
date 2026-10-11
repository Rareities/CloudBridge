package io.github.rareities.protonbridge;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class RcResponseCompatibilityTest {
    @Test public void pinnedEngineExecutionIdentityDoesNotBreakJobAcknowledgement() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        Class<?> response = Class.forName("io.github.rareities.protonbridge.RcloneRcd$JobIdResponse");
        Object parsed = mapper.readValue("{\"executeId\":\"native-instance\",\"jobid\":17}", response);
        java.lang.reflect.Field field = response.getDeclaredField("jobid");
        field.setAccessible(true);
        assertEquals(17, field.getInt(parsed));
        RcloneRcd.JobListResponse jobs = mapper.readValue("{\"executeId\":\"native-instance\",\"jobids\":[17]}", RcloneRcd.JobListResponse.class);
        assertArrayEquals(new int[]{17}, jobs.jobids);
        // Missing acknowledgement still produces invalid job zero, rejected by submitJob.
        assertEquals(0, field.getInt(mapper.readValue("{\"executeId\":\"native-instance\"}", response)));
    }
}
