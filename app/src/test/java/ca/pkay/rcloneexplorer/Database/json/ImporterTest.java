package ca.pkay.rcloneexplorer.Database.json;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImporterTest {

    @Test
    public void missingLegacyArraysAreTreatedAsEmpty() throws Exception {
        Importer.ParsedImport parsed = Importer.parse(
                "{\"tasks\":[{\"id\":10,\"title\":\"copy\"}]}"
        );

        assertEquals(1, parsed.getTasks().size());
        assertEquals(0, parsed.getFilters().size());
        assertEquals(0, parsed.getTriggers().size());
    }

    @Test
    public void explicitEmptyTasksArrayIsAccepted() throws Exception {
        Importer.ParsedImport parsed = Importer.parse(
                "{\"tasks\":[],\"filters\":[],\"trigger\":[]}"
        );

        assertEquals(0, parsed.getTasks().size());
    }

    @Test(expected = JSONException.class)
    public void missingTasksArrayIsRejectedBeforeReplacement() throws Exception {
        Importer.validate("{\"filters\":[],\"trigger\":[]}");
    }

    @Test(expected = JSONException.class)
    public void duplicateTaskIdsAreRejectedBeforeMutation() throws Exception {
        Importer.validate(
                "{\"tasks\":[{\"id\":10},{\"id\":10}],\"filters\":[],\"trigger\":[]}"
        );
    }

    @Test(expected = JSONException.class)
    public void unknownReferencesAreRejectedBeforeMutation() throws Exception {
        Importer.validate(
                "{\"tasks\":[{\"id\":10,\"filterId\":99}],\"filters\":[],\"trigger\":[]}"
        );
    }

    @Test(expected = JSONException.class)
    public void oversizedImportIsRejected() throws Exception {
        StringBuilder input = new StringBuilder(Importer.MAX_IMPORT_CHARS + 32);
        input.append('{').append('\"').append('x').append('\"').append(':').append('\"');
        while (input.length() <= Importer.MAX_IMPORT_CHARS) {
            input.append('x');
        }
        input.append('\"').append('}');
        Importer.validate(input.toString());
    }
}
