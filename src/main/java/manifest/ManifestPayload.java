package manifest;

import constants.SegmentAction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ManifestPayload {
    List<SegmentChangeLog> segmentChangeLogs;

    public ManifestPayload(List<SegmentChangeLog> segmentChangeLogs){
        this.segmentChangeLogs = segmentChangeLogs;
    }

    public byte[] encode() throws IOException {
        int totalLogs = segmentChangeLogs.size();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.writeInt(totalLogs);
        for(SegmentChangeLog segmentChangeLog: segmentChangeLogs){
            String segmentId = segmentChangeLog.segmentId();
            byte[] segmentIdBytes = segmentId.getBytes(StandardCharsets.UTF_8);
            dataOutputStream.writeInt(segmentIdBytes.length);
            dataOutputStream.write(segmentIdBytes);

            SegmentAction segmentAction = segmentChangeLog.segmentAction();
            byte[] segmentActionBytes = segmentAction.getAction().getBytes(StandardCharsets.UTF_8);
            dataOutputStream.writeInt(segmentActionBytes.length);
            dataOutputStream.write(segmentActionBytes);
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static ManifestPayload decode(byte[] data) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        int totalLogs = byteBuffer.getInt();
        if(totalLogs < 0) throw new IOException("Total logs inside a manifest payload can't be negative");
        List<SegmentChangeLog> segmentChangeLogList = new ArrayList<>();
        for(int i=0;i<totalLogs;i++){
            int segmentIdLen = byteBuffer.getInt();
            if(segmentIdLen <=0) throw new IOException("Segment id length can't be less than equal to 0");
            if(segmentIdLen > byteBuffer.remaining()) throw new IOException("Segment id length can't be greater than remaining payload");
            byte[] segmentIdBytes = new byte[segmentIdLen];
            byteBuffer.get(segmentIdBytes);
            String segmentId = new String(segmentIdBytes, StandardCharsets.UTF_8);

            int segmentActionLen = byteBuffer.getInt();
            if(segmentActionLen <=0 ) throw new IOException("Segment action length can't be less than equal to 0");
            if(segmentActionLen > byteBuffer.remaining()) throw new IOException("Segment action length can't be greater than remaining payload");
            byte[] segmentActionBytes = new byte[segmentActionLen];
            byteBuffer.get(segmentActionBytes);
            SegmentAction segmentAction = SegmentAction.fromAction(new String(segmentActionBytes, StandardCharsets.UTF_8));

            segmentChangeLogList.add(new SegmentChangeLog(segmentId, segmentAction));
        }

        if(byteBuffer.hasRemaining()) throw new IOException("Unexpected residual bytes found in manifest payload");

        return new ManifestPayload(segmentChangeLogList);
    }
}
