package snow.rebuild;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

final class OpenCvBoardVision implements BoardVision {
    static BoardVision load() {
        try {
            System.loadLibrary("opencv_java4");
            return new OpenCvBoardVision();
        } catch (LinkageError e) {
            return BoardVision::scan;
        }
    }

    @Override public List<Region> regions(byte[] mask, int width, int height) {
        Mat image=new Mat(height,width,CvType.CV_8UC1);
        Mat labels=new Mat(), stats=new Mat(), centroids=new Mat();
        try {
            image.put(0,0,mask);
            int count=Imgproc.connectedComponentsWithStats(image,labels,stats,centroids,8,CvType.CV_32S);
            int[] row=new int[5];
            ArrayList<Region> result=new ArrayList<>();
            for (int i=1; i<count; i++) {
                stats.get(i,0,row);
                if (BoardVision.iconSized(row[2],row[3],row[4])) {
                    result.add(new Region(row[0],row[1],row[2],row[3],row[4]));
                }
            }
            return result;
        } finally {
            centroids.release();
            stats.release();
            labels.release();
            image.release();
        }
    }
}
