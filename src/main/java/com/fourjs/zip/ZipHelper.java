package com.fourjs.zip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * Helper that wraps the <a href="https://github.com/srikanth-lingala/zip4j">Zip4j</a>
 * library behind a small, Genero-BDL-friendly surface.
 *
 * <p>Every public method takes and returns only primitives and {@code String}s so
 * the class can be driven directly from BDL via {@code IMPORT JAVA}. In particular:
 * <ul>
 *   <li>No Java collections or arrays cross the boundary &mdash; archive listings are
 *       exposed as a count plus index accessors ({@link #loadEntries()},
 *       {@link #getEntryName(int)} &hellip;).</li>
 *   <li>In-memory content is exchanged as Base64 {@code String}s, which map cleanly to
 *       BDL {@code STRING}.</li>
 *   <li>Compression and encryption options are selected by name, not by passing Java
 *       enum constants.</li>
 * </ul>
 *
 * <p>The {@link #create(Operation, String)} factory mirrors the original API; it exists
 * because BDL constructs Java objects through a static {@code create()} rather than
 * {@code new}.
 */
public class ZipHelper {

   private static final int BUFFER = 8192;

   /** Whether this helper is configured to build an archive or to read one. */
   public static enum Operation {
      Zip,
      Unzip
   }

   /** Result of {@link #ready()}: either {@code Ready} or the first missing prerequisite. */
   public static enum ReadyStatus {
      OperationMissing,
      ZipFileMissing,
      TargetDirMissing,
      FileListEmpty,
      Ready
   }

   private Operation operation;
   private String zipFile;
   private String targetDir;
   private final List<String> fileList = new ArrayList<String>();

   private char[] password; // null => no encryption
   private CompressionLevel compressionLevel = CompressionLevel.NORMAL;
   private CompressionMethod compressionMethod = CompressionMethod.DEFLATE;
   private EncryptionMethod encryptionMethod = EncryptionMethod.AES;

   // Populated by loadEntries(); read back through the getEntry* accessors.
   private List<FileHeader> loadedHeaders = new ArrayList<FileHeader>();

   /** BDL-facing constructor: {@code ZipHelper.create(Operation.Zip, "out.zip")}. */
   public static ZipHelper create(Operation operation, String zipFile) {
      return new ZipHelper(operation, zipFile);
   }

   public ZipHelper(Operation operation, String zipFile) {
      this.operation = operation;
      this.zipFile = zipFile;
   }

   // ---- Getters and setters -------------------------------------------------

   public Operation getOperation() {
      return this.operation;
   }

   public void setOperation(Operation operation) {
      this.operation = operation;
   }

   public String getZipFile() {
      return this.zipFile;
   }

   public void setZipFile(String zipFile) {
      this.zipFile = zipFile;
   }

   public String getTargetDir() {
      return this.targetDir;
   }

   public void setTargetDir(String targetDir) {
      this.targetDir = targetDir;
   }

   // ---- File list management ------------------------------------------------

   public boolean addFile(String file) {
      return fileList.add(file);
   }

   public void clearFileList() {
      fileList.clear();
   }

   public int getFileCount() {
      return fileList.size();
   }

   public String getFileAt(int index) {
      return fileList.get(index);
   }

   // ---- Password / compression / encryption options ------------------------

   /** Set the archive password. A {@code null} or empty value disables encryption. */
   public void setPassword(String pw) {
      this.password = (pw == null || pw.isEmpty()) ? null : pw.toCharArray();
   }

   public boolean hasPassword() {
      return this.password != null;
   }

   /**
    * Select the compression level by name (case-insensitive): one of
    * {@code NO_COMPRESSION}, {@code FASTEST}, {@code FAST}, {@code NORMAL},
    * {@code MAXIMUM}, {@code ULTRA}. Returns {@code false} for an unknown name.
    */
   public boolean setCompressionLevel(String name) {
      try {
         this.compressionLevel = CompressionLevel.valueOf(name.trim().toUpperCase());
         return true;
      } catch (IllegalArgumentException | NullPointerException e) {
         return false;
      }
   }

   /**
    * Select the compression method by name (case-insensitive): {@code STORE}
    * (no compression) or {@code DEFLATE}. Returns {@code false} for an unknown name.
    */
   public boolean setCompressionMethod(String name) {
      try {
         this.compressionMethod = CompressionMethod.valueOf(name.trim().toUpperCase());
         return true;
      } catch (IllegalArgumentException | NullPointerException e) {
         return false;
      }
   }

   /**
    * Select the encryption method used when a password is set (case-insensitive):
    * {@code AES} or {@code ZIP_STANDARD}. Returns {@code false} for an unknown name.
    */
   public boolean setEncryptionMethod(String name) {
      try {
         this.encryptionMethod = EncryptionMethod.valueOf(name.trim().toUpperCase());
         return true;
      } catch (IllegalArgumentException | NullPointerException e) {
         return false;
      }
   }

   // ---- Readiness validation ------------------------------------------------

   public ReadyStatus ready() {
      if (operation == null) {
         return ReadyStatus.OperationMissing;
      }
      if (zipFile == null) {
         return ReadyStatus.ZipFileMissing;
      }
      if (operation == Operation.Unzip && targetDir == null) {
         return ReadyStatus.TargetDirMissing;
      }
      if (operation == Operation.Zip && fileList.isEmpty()) {
         return ReadyStatus.FileListEmpty;
      }
      return ReadyStatus.Ready;
   }

   public String getReadyStatusMessage() {
      switch (ready()) {
         case OperationMissing:
            return "Operation (zip or unzip) is missing";
         case ZipFileMissing:
            return "Zip file name is missing";
         case TargetDirMissing:
            return "Unzip target directory name is missing";
         case FileListEmpty:
            return "Zip file list is empty";
         case Ready:
            return "All parameters are set";
         default:
            return "Unknown status";
      }
   }

   // ---- Internal helpers ----------------------------------------------------

   private ZipFile openZip() {
      return (password != null) ? new ZipFile(zipFile, password) : new ZipFile(zipFile);
   }

   private ZipParameters buildParameters() {
      ZipParameters params = new ZipParameters();
      params.setCompressionLevel(compressionLevel);
      params.setCompressionMethod(compressionMethod);
      if (password != null) {
         params.setEncryptFiles(true);
         params.setEncryptionMethod(encryptionMethod);
      }
      return params;
   }

   // ---- Whole-archive create / extract -------------------------------------

   /** Build the archive from every path in the file list (files and folders). */
   public boolean createZipFolder() throws Exception {
      if (ready() != ReadyStatus.Ready || operation != Operation.Zip) {
         return false;
      }
      ZipFile zip = openZip();
      try {
         ZipParameters params = buildParameters();
         for (String entry : fileList) {
            File source = new File(entry);
            if (source.isDirectory()) {
               zip.addFolder(source, params);
            } else {
               zip.addFile(source, params);
            }
         }
      } finally {
         zip.close();
      }
      return true;
   }

   /**
    * Extract the whole archive into {@link #getTargetDir()}. On success the file list
    * is replaced with the paths of the extracted entries (parity with the original API).
    */
   public boolean extractFolder() throws Exception {
      if (ready() != ReadyStatus.Ready || operation != Operation.Unzip) {
         return false;
      }
      ZipFile zip = openZip();
      try {
         zip.extractAll(targetDir);
         clearFileList();
         for (FileHeader header : zip.getFileHeaders()) {
            addFile(new File(targetDir, header.getFileName()).getPath());
         }
      } finally {
         zip.close();
      }
      return true;
   }

   // ---- Single-entry add / extract -----------------------------------------

   /** Add one file or folder to the (possibly already existing) archive. */
   public boolean addEntry(String filePath) throws Exception {
      ZipFile zip = openZip();
      try {
         ZipParameters params = buildParameters();
         File source = new File(filePath);
         if (source.isDirectory()) {
            zip.addFolder(source, params);
         } else {
            zip.addFile(source, params);
         }
      } finally {
         zip.close();
      }
      return true;
   }

   /** Extract a single named entry from the archive into {@code destDir}. */
   public boolean extractEntry(String entryName, String destDir) throws Exception {
      ZipFile zip = openZip();
      try {
         zip.extractFile(entryName, destDir);
      } finally {
         zip.close();
      }
      return true;
   }

   // ---- Listing (no extraction) --------------------------------------------

   /**
    * Read the archive's central directory and cache the entry headers for the
    * {@code getEntry*} accessors. Returns the number of entries.
    */
   public int loadEntries() throws Exception {
      ZipFile zip = openZip();
      try {
         loadedHeaders = zip.getFileHeaders();
      } finally {
         zip.close();
      }
      return loadedHeaders.size();
   }

   public int getEntryCount() {
      return loadedHeaders.size();
   }

   /** Entry name at the given 0-based index (call {@link #loadEntries()} first). */
   public String getEntryName(int index) {
      return loadedHeaders.get(index).getFileName();
   }

   public long getEntrySize(int index) {
      return loadedHeaders.get(index).getUncompressedSize();
   }

   public long getEntryCompressedSize(int index) {
      return loadedHeaders.get(index).getCompressedSize();
   }

   public boolean isEntryDirectory(int index) {
      return loadedHeaders.get(index).isDirectory();
   }

   public boolean isEntryEncrypted(int index) {
      return loadedHeaders.get(index).isEncrypted();
   }

   // ---- In-memory / Base64 exchange ----------------------------------------

   /** Add an entry named {@code entryName} whose content is the decoded Base64 string. */
   public boolean addBase64Entry(String entryName, String base64Content) throws Exception {
      byte[] data = Base64.getDecoder().decode(base64Content);
      ZipFile zip = openZip();
      try {
         ZipParameters params = buildParameters();
         params.setFileNameInZip(entryName);
         InputStream in = new ByteArrayInputStream(data);
         try {
            zip.addStream(in, params);
         } finally {
            in.close();
         }
      } finally {
         zip.close();
      }
      return true;
   }

   /** Return the content of a single named entry as a Base64 string. */
   public String getEntryAsBase64(String entryName) throws Exception {
      ZipFile zip = openZip();
      try {
         FileHeader header = zip.getFileHeader(entryName);
         if (header == null) {
            throw new FileNotFoundException("Entry not found in archive: " + entryName);
         }
         ByteArrayOutputStream bos = new ByteArrayOutputStream();
         InputStream in = zip.getInputStream(header);
         try {
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) != -1) {
               bos.write(buf, 0, read);
            }
         } finally {
            in.close();
         }
         return Base64.getEncoder().encodeToString(bos.toByteArray());
      } finally {
         zip.close();
      }
   }

   /** Read the archive file from disk and return its raw bytes as Base64. */
   public String zipFileToBase64() throws Exception {
      byte[] bytes = Files.readAllBytes(new File(zipFile).toPath());
      return Base64.getEncoder().encodeToString(bytes);
   }

   /** Decode a Base64 archive payload and write it to {@link #getZipFile()} on disk. */
   public boolean writeZipFromBase64(String base64) throws Exception {
      byte[] bytes = Base64.getDecoder().decode(base64);
      FileOutputStream fos = new FileOutputStream(zipFile);
      try {
         fos.write(bytes);
      } finally {
         fos.close();
      }
      return true;
   }
}
