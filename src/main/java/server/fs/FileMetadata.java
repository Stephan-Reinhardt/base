package server.fs;

public class FileMetadata {

    private final long size;
    private final String filePath;

    public FileMetadata(long size, String filePath) {
        this.size = size;
        this.filePath = filePath;
    }

    public long getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "FileMetadata{" +
                "size=" + size +
                ", filePath='" + filePath + '\'' +
                '}';
    }
}