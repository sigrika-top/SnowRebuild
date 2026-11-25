package snow.rebuild;

import java.util.ArrayList;
import java.util.List;

interface BoardVision {
    record Region(int x, int y, int width, int height, int area) {}

    List<Region> regions(byte[] mask, int width, int height);

    static boolean iconSized(int width, int height, int area) {
        return width >= 5 && width <= 24 && height >= 5 && height <= 24 && area >= 23;
    }

    static List<Region> scan(byte[] mask, int width, int height) {
        ArrayList<Region> regions = new ArrayList<>();
        int[] pending = new int[mask.length];
        byte[] remaining = mask.clone();
        for (int i=0; i<remaining.length; i++) {
            if (remaining[i] == 0) continue;
            int count=1, left=width, top=height, right=0, bottom=0;
            pending[0]=i;
            remaining[i]=0;
            for (int j=0; j<count; j++) {
                int x=pending[j]%width, y=pending[j]/width;
                left=Math.min(left,x); right=Math.max(right,x+1);
                top=Math.min(top,y); bottom=Math.max(bottom,y+1);
                for (int dy=-1; dy<=1; dy++) {
                    for (int dx=-1; dx<=1; dx++) {
                        int xx=x+dx, yy=y+dy, k=yy*width+xx;
                        if (xx<0 || xx>=width || yy<0 || yy>=height || remaining[k]==0) continue;
                        remaining[k]=0;
                        pending[count++]=k;
                    }
                }
            }
            if (iconSized(right-left,bottom-top,count)) {
                regions.add(new Region(left,top,right-left,bottom-top,count));
            }
        }
        return regions;
    }
}
