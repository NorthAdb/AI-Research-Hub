package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aireshub.document.LocalObjectStorage;
import com.aireshub.document.SupabaseObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ObjectStorageTest {

    @Test
    void localRoundTripStaysInsideTheRoot() throws Exception {
        Path root = Files.createTempDirectory("arh-storage");
        LocalObjectStorage storage = new LocalObjectStorage(root);
        UUID kbId = UUID.randomUUID();
        byte[] content = "夜航船".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String key = storage.write(kbId, "note.md", content);
        assertTrue(key.contains(kbId.toString()));
        assertArrayEquals(content, storage.read(key));
        storage.delete(key);
        assertTrue(Files.notExists(root.resolve(key)));
    }

    @Test
    void localReadRejectsPathEscape() throws Exception {
        Path root = Files.createTempDirectory("arh-storage");
        LocalObjectStorage storage = new LocalObjectStorage(root);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> storage.read("../secret.txt"));
    }

    @Test
    void supabaseUploadUsesTheStorageRestPath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supabase.test/storage/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseObjectStorage storage = new SupabaseObjectStorage(builder.build(), "documents");
        UUID kbId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        server.expect(request -> assertTrue(request.getURI().getPath().contains("/object/documents/" + kbId)))
                .andExpect(request -> assertTrue(HttpMethod.POST.equals(request.getMethod())))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{}", MediaType.APPLICATION_JSON));

        String key = storage.write(kbId, "note.md", "hi".getBytes());
        assertTrue(key.startsWith(kbId + "/"));
        assertTrue(key.endsWith("_note.md"));
        server.verify();
    }
}
