package ca.pkay.rcloneexplorer.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProtectedBackupTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private final char[] password = "disposable-backup-password".toCharArray();
    private Map<String,byte[]> fixture() {
        Map<String,byte[]> parts=new HashMap<>();
        parts.put("rclone.conf", "CANARY-CONFIG-secret".getBytes(StandardCharsets.UTF_8));
        parts.put("rcx.json", "CANARY-PRIVATE-FOLDER-name".getBytes(StandardCharsets.UTF_8));
        parts.put("rcx.prefs", "CANARY-PROXY-PASSWORD-secret".getBytes(StandardCharsets.UTF_8));
        return parts;
    }
    @Test public void allComponentsAreEncryptedAndWrongPasswordChangesNothing() throws Exception {
        File file=directory.newFile(); Map<String,byte[]> parts=fixture();
        ProtectedBackup.write(file,password,parts);
        byte[] original=Files.readAllBytes(file.toPath());
        String container=new String(original,StandardCharsets.ISO_8859_1);
        for(byte[] bytes:parts.values()) assertFalse(container.contains(new String(bytes,StandardCharsets.UTF_8)));
        assertTrue(ProtectedBackup.isEncrypted(file));
        Map<String,byte[]> recovered=ProtectedBackup.read(file,password);
        for(String name:parts.keySet()) assertArrayEquals(parts.get(name),recovered.get(name));
        assertThrows(IOException.class,()->ProtectedBackup.read(file,"wrong-but-long-password".toCharArray()));
        assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        ProtectedBackup.clear(recovered); assertTrue(recovered.isEmpty());
    }
    @Test public void tamperedPayloadCannotPassAuthentication() throws Exception {
        File file=directory.newFile(); ProtectedBackup.write(file,password,fixture());
        byte[] bytes=Files.readAllBytes(file.toPath());
        // First local header, followed by filename/extra then the encrypted salt/payload.
        int name=(bytes[26]&255)|((bytes[27]&255)<<8);
        int extra=(bytes[28]&255)|((bytes[29]&255)<<8);
        bytes[30+name+extra+20]^=1;
        Files.write(file.toPath(),bytes);
        assertThrows(IOException.class,()->ProtectedBackup.read(file,password));
    }
    @Test public void unencryptedOrUnexpectedEntriesAreRejectedWithoutExtraction() throws Exception {
        File file=directory.newFile(); ProtectedBackup.write(file,password,fixture());
        try(ZipFile zip=new ZipFile(file,password)) {
            ZipParameters parameters=new ZipParameters(); parameters.setFileNameInZip("../outside.txt");
            parameters.setEncryptFiles(true); parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            zip.addStream(new ByteArrayInputStream("unexpected".getBytes(StandardCharsets.UTF_8)),parameters);
        }
        assertThrows(IOException.class,()->ProtectedBackup.read(file,password));
        assertFalse(new File(directory.getRoot().getParentFile(),"outside.txt").exists());
    }
    @Test public void oversizeAndIncompleteOutputAreRejectedBeforeCreatingAnArchive() throws Exception {
        File file=new File(directory.getRoot(),"new.zip"); Map<String,byte[]> parts=fixture();
        parts.put("rcx.prefs",new byte[256*1024+1]);
        assertThrows(IOException.class,()->ProtectedBackup.write(file,password,parts)); assertFalse(file.exists());
        parts.remove("rcx.prefs");
        assertThrows(IOException.class,()->ProtectedBackup.write(file,password,parts)); assertFalse(file.exists());
    }
}
