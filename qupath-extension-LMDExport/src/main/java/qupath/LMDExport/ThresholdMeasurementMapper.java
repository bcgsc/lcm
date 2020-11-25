
package qupath.LMDExport;

        import org.slf4j.Logger;
        import org.slf4j.LoggerFactory;
        import qupath.lib.common.ColorTools;
        import qupath.lib.gui.helpers.MeasurementMapper;
        import qupath.lib.objects.PathDetectionObject;
        import qupath.lib.objects.PathObject;
        import qupath.lib.objects.PathTileObject;
        import qupath.lib.objects.helpers.PathObjectColorToolsAwt;

        import java.util.Collection;

/**
 * Created by cschlosser on 18/09/2017.
 * This class is used to  allow a custom painting of objects compared to the MeasurementMapper class it extends
 */
public class ThresholdMeasurementMapper extends MeasurementMapper {



    final private static Logger logger = LoggerFactory.getLogger(MeasurementMapper.class);

    private ColorMapper colorMapper = new ThresholdColorMapper();



    public ThresholdMeasurementMapper(String measurement, Collection<PathObject> pathObjects) {
        super( measurement,  pathObjects);
    }


    public Integer getColorForObject(PathObject pathObject) {


        if (!(pathObject instanceof PathDetectionObject || pathObject instanceof PathTileObject))
            return PathObjectColorToolsAwt.getDisplayedColor(pathObject);

        // Replace NaNs with the minimum value
        double value = getUsefulValue(pathObject, Double.NaN);

        if (super.getExcludeOutsideRange() && value < super.getDisplayMinValue())
            return ColorTools.makeRGB(255,255,0);
        if (super.getExcludeOutsideRange() && value > super.getDisplayMaxValue())
            return ColorTools.makeRGB(0,255,255);

        if (Double.isNaN(value))
            return null;

        // Map value to color
        return colorMapper.getColor(value, super.getDisplayMinValue(), super.getDisplayMaxValue());
    }



    public static class ThresholdColorMapper implements MeasurementMapper.ColorMapper {

        private static final int[] r = {255, 255, 255, 255, 255, 255};
        private static final int[] g = {0, 0, 0, 0, 0, 0};
        private static final int[] b = {255, 255, 255, 255, 255, 255};
        private static int nColors = 256;
        private static Integer[] colors = new Integer[nColors];

        static {
            double scale = (double) (r.length - 1) / nColors;
            for (int i = 0; i < nColors; i++) {
                int ind = (int) (i * scale);
                double residual = (i * scale) - ind;
                colors[i] = ColorTools.makeRGB(
                        r[ind] + (int) ((r[ind + 1] - r[ind]) * residual),
                        g[ind] + (int) ((g[ind + 1] - g[ind]) * residual),
                        b[ind] + (int) ((b[ind + 1] - b[ind]) * residual));
            }
            colors[nColors - 1] = ColorTools.makeRGB(r[r.length - 1], g[g.length - 1], b[b.length - 1]);
        }


        @Override
        public Integer getColor(int ind) {
            Integer color = colors[ind];
            if (color == null) {
                color = ColorTools.makeRGB(r[ind], g[ind], b[ind]);
                colors[ind] = color;
            }
            return color;
        }

        @Override
        public Integer getColor(double value, double minValue, double maxValue) {
            //			System.out.println("Measurement mapper: " + minValue + ", " + maxValue);
            int ind = 0;
            if (maxValue > minValue) {
                ind = (int) ((value - minValue) / (maxValue - minValue) * nColors + .5);
                ind = ind >= nColors ? nColors - 1 : ind;
                ind = ind < 0 ? 0 : ind;
            }
            return getColor(ind);
        }


        @Override
        public boolean hasAlpha() {
            return false;
        }


    }



}

