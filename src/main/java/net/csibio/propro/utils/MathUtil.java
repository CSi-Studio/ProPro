package net.csibio.propro.utils;

import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.common.Pair;
import net.csibio.propro.domain.bean.data.RtIntensityPairsDouble;
import net.csibio.propro.domain.bean.math.BisectionLowHigh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;

/**
 * Created by Nico Wang Ruimin
 * Time: 2018-07-28 21-30
 */
public class MathUtil {

    public static final Logger logger = LoggerFactory.getLogger(MathUtil.class);

    public static double getRsq(List<Pair> pairs) {
        double sigmaX = 0d;
        double sigmaY = 0d;
        double sigmaXSquare = 0d;
        double sigmaYSquare = 0d;
        double sigmaXY = 0d;
        double x, y;
        int n = pairs.size();
        for (int i = 0; i < n; i++) {
            x = pairs.get(i).right();//RunRt
            y = pairs.get(i).left();//TheoryRt
            sigmaX += x;
            sigmaY += y;
            sigmaXY += x * y;
            sigmaXSquare += x * x;
            sigmaYSquare += y * y;
        }

        double r = (n * sigmaXY - sigmaX * sigmaY) / Math.sqrt((n * sigmaXSquare - sigmaX * sigmaX) * (n * sigmaYSquare - sigmaY * sigmaY));
        return r * r;
    }

    public static BisectionLowHigh bisection(double[] x, double value) {

        int high = x.length - 1;
        int low = 0;
        int mid;

        if (value < x[0]) {
            high = 0;
        } else if (value > x[x.length - 1]) {
            low = x.length - 1;
        } else {
            while (high - low != 1) {
                mid = low + (high - low + 1) / 2;
                if (x[mid] < value) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
        }
        return new BisectionLowHigh(low, high);
    }

    public static BisectionLowHigh bisection(Double[] x, double value) {
        if (x.length == 1) {
            return new BisectionLowHigh(0, 0);
        }
        int high = x.length - 1;
        int low = 0;
        int mid;

        if (value < x[0]) {
            high = 0;
        } else if (value > x[x.length - 1]) {
            low = x.length - 1;
        } else {
            while (high - low != 1) {
                mid = low + (high - low + 1) / 2;
                if (x[mid] < value) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
        }
        return new BisectionLowHigh(low, high);
    }

    public static BisectionLowHigh bisection(float[] x, float value) {
        if (x.length == 1) {
            return new BisectionLowHigh(0, 0);
        }
        int high = x.length - 1;
        int low = 0;
        int mid;

        if (value < x[0]) {
            high = 0;
        } else if (value > x[x.length - 1]) {
            low = x.length - 1;
        } else {
            while (high - low != 1) {
                mid = low + (high - low + 1) / 2;
                if (x[mid] < value) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
        }
        return new BisectionLowHigh(low, high);
    }

    public static BisectionLowHigh bisectionBD(List<BigDecimal> x, double value) {
        int high = x.size() - 1;
        int low = 0;
        int mid;

        if (x.get(0).compareTo(new BigDecimal(Double.toString(value))) > 0) {
            high = 0;
        } else if (x.get(x.size() - 1).compareTo(new BigDecimal(Double.toString(value))) < 0) {
            low = x.size() - 1;
        } else {
            while (high - low != 1) {
                mid = low + (high - low + 1) / 2;
                if (x.get(mid).compareTo(new BigDecimal(Double.toString(value))) < 0) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
        }
        return new BisectionLowHigh(low, high);
    }

    public static BisectionLowHigh bisection(RtIntensityPairsDouble x, double value) {
        int high = x.getRtArray().length - 1;
        int low = 0;
        int mid;

        if (high != 0) {
            if (x.getRtArray()[0] > value) {
                high = 0;
            } else if (x.getRtArray()[x.getRtArray().length - 1] < value) {
                low = x.getRtArray().length - 1;
            } else {
                while (high - low != 1) {
                    mid = low + (high - low + 1) / 2;
                    if (x.getRtArray()[mid] < value) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
            }
        }
        return new BisectionLowHigh(low, high);
    }

    /**
     * (data - mean) / std
     */
    public static double[] standardizeData(List<Double> data) {
        int dataLength = data.size();

        //get mean
        double sum = 0d;
        for (double value : data) {
            sum += value;
        }
        double mean = sum / dataLength;

        //get std
        sum = 0f;
        for (double value : data) {
            sum += (value - mean) * (value - mean);
        }
        double std = Math.sqrt(sum / dataLength);

        //get standardized data
        double[] standardizedData = new double[dataLength];
        for (int i = 0; i < dataLength; i++) {
            if (std == 0) {
                standardizedData[i] = 0;
            } else {
                standardizedData[i] = (data.get(i) - mean) / std;
            }
        }
        return standardizedData;
    }

    /**
     * (data - mean) / std
     */
    public static double[] standardizeData(Double[] data) {
        int dataLength = data.length;

        //get mean
        double sum = 0d;
        for (double value : data) {
            sum += value;
        }
        double mean = sum / dataLength;

        //get std
        sum = 0f;
        for (double value : data) {
            sum += (value - mean) * (value - mean);
        }
        double std = Math.sqrt(sum / dataLength);

        //get standardized data
        double[] standardizedData = new double[dataLength];
        for (int i = 0; i < dataLength; i++) {
            if (std == 0) {
                standardizedData[i] = 0;
            } else {
                standardizedData[i] = (data[i] - mean) / std;
            }
        }
        return standardizedData;
    }

    /**
     * (data - mean) / std
     */
    public static double[] standardizeDataOfdouble(double[] data) {
        int dataLength = data.length;

        //get mean
        double sum = 0d;
        for (double value : data) {
            sum += value;
        }
        double mean = sum / dataLength;

        //get std
        sum = 0f;
        for (double value : data) {
            sum += (value - mean) * (value - mean);
        }
        double std = Math.sqrt(sum / dataLength);

        //get standardized data
        double[] standardizedData = new double[dataLength];
        for (int i = 0; i < dataLength; i++) {
            if (std == 0) {
                standardizedData[i] = 0;
            } else {
                standardizedData[i] = (data[i] - mean) / std;
            }
        }
        return standardizedData;
    }

    public static int findMaxIndex(Double[] data) {
        if (data == null || data.length == 0) {
            return -1;
        }
        double max = data[0];
        int index = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] > max) {
                max = data[i];
                index = i;
            }
        }
        return index;
    }

    public static int findMaxIndex(List<Double> data) {
        if (data == null || data.size() == 0) {
            return -1;
        }
        double max = data.get(0);
        int index = 0;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i) > max) {
                max = data.get(i);
                index = i;
            }
        }
        return index;
    }


    public static int log2n(int value) {
        int log2n = 0;
        while (value > Math.pow(2, log2n)) {
            log2n++;
        }
        return log2n;
    }

    public static void renormalize(List<Double> doubleList) {
        double sum = 0.0d;
        for (double value : doubleList) {
            sum += value;
        }
        for (int i = 0; i < doubleList.size(); i++) {
            doubleList.set(i, doubleList.get(i) / sum);
        }
    }

    public static Map<String, Double> normalizeMap(Map<String, Double> doubleMap) {
        double sum = 0.0d;
        for (double value : doubleMap.values()) {
            sum += value;
        }
        double finalSum = sum;
        HashMap<String, Double> map = new HashMap<>();
        doubleMap.keySet().forEach(key -> {
            map.put(key, doubleMap.get(key) / finalSum);
        });
        return map;
    }

    /**
     * 求出数组的平均值和方差
     *
     * @param arrays k,v
     * @return 0:mean 1:variance
     */
    public static double[] getMeanVariance(Double[] arrays) {

        double[] meanVariance = new double[2];
        //get mean
        double mean = mean(arrays);
        meanVariance[0] = mean;
        meanVariance[1] = var(arrays, mean);

        return meanVariance;
    }

    /**
     * Normalize a with a's mean and std.
     */
    public static List<Float> normalizeSum(Map<String, Float> map) {
        List<Float> normedList = new ArrayList<>();
        Float sum = 0f;
        for (Float f : map.values()) {
            sum += f;
        }
        for (String key : map.keySet()) {
            normedList.add(map.get(key) / sum);
        }
        return normedList;
    }

    /**
     * Normalize a with a's mean and std.
     */
    public static Double[] normalize(Double[] array) {
        Double[] result = array.clone();
        double mean = mean(array);
        double std = std(array, mean);
        for (int i = 0; i < array.length; i++) {
            result[i] = (result[i] - mean) / std;
        }
        return result;
    }

    public static double[] normalize(double[] array, double value) {
        if (value > 0) {
            for (int i = 0; i < array.length; i++) {
                array[i] /= value;
            }
        }
        return array;
    }

    /**
     * Normalize a with a's mean and std.
     */
    public static double[] normalizeAsPrimitive(Double[] array) {
//        Double[] result = array.clone();
        double[] result = new double[array.length];
        double mean = mean(array);
        double std = std(array, mean);
        for (int i = 0; i < array.length; i++) {
            result[i] = (array[i] - mean) / std;
        }
        return result;
    }

    /**
     * Normalize a with b's mean and std.
     */
    public static Double[] normalize(Double[] arrayA, Double[] arrayB) {
        double mean = mean(arrayB);
        double std = std(arrayB, mean);
        Double[] result = arrayA.clone();
        for (int i = 0; i < arrayA.length; i++) {
            result[i] = (result[i] - mean) / std;
        }
        return result;
    }

    //求平均值,如果是NaN的直接跳过
    public static double mean(Double[] array) {
        int n = array.length;
        double sum = 0;
        for (Double i : array) {
            if (i.isNaN()) {
                n--;
            } else {
                sum += i;
            }
        }
        if (n <= 0) {
            logger.error("All the data in array are all NaN!!!");
        }
        return sum / n;
    }

    //求标准差,如果是NaN的直接跳过
    public static double std(Double[] array) {
        double mean = mean(array);
        return std(array, mean);
    }

    //已知平均值,求标准差,如果是NaN的直接跳过,自由度为n-1
    public static double std(Double[] array, double mean) {
        int n = array.length;
        double var = 0;
        for (Double i : array) {
            if (i.isNaN()) {
                n--;
            } else {
                var += Math.pow(i - mean, 2);
            }
        }
        if (n <= 0) {
            logger.error("All of the data in array are NaN!!!");
        }
        var = var / n;
        return Math.sqrt(var);
    }

    //求方差,如果是NaN的直接跳过
    public static double var(Double[] array) {
        double mean = mean(array);
        return var(array, mean);
    }

    //已知平均值,求方差,如果是NaN的直接跳过
    public static double var(Double[] array, double mean) {
        int n = array.length;
        double var = 0;
        for (Double i : array) {
            if (i.isNaN()) {
                n--;
            } else {
                var += Math.pow(i - mean, 2);
            }
        }
        if (n <= 0) {
            logger.error("All of the data in array are NaN!!!");
        }
        var /= n;
        return var;
    }

    public static Double[] dot(Double[][] array, Double[] w) {
        int aLength = array.length;
        int wLength = w.length;
        if (array[0].length == wLength) {
            Double[] result = new Double[aLength];
            for (int i = 0; i < aLength; i++) {
                result[i] = 0.0;
                for (int j = 0; j < wLength; j++) {
                    result[i] += array[i][j] * w[j];
                }
            }
            return result;
        } else {
            logger.error("Dot Error");
            return null;
        }
    }

    /**
     * Error function erf().
     */
    public static double erf(double t) {
        double result = 0.0;
        for (int i = 1; i < 101; i++) {
            result += t * 2.0 * Math.exp(-Math.pow(i * t / 100, 2)) / Math.sqrt(Math.PI) / 100.0;
        }
        return result;
    }

    public static double sum(double[] array) {
        double sum = 0;
        for (double value : array) {
            sum += value;
        }
        return sum;
    }

    public static double sum(List<Double> array) {
        double sum = 0;
        for (double value : array) {
            sum += value;
        }
        return sum;
    }

    public static double sumDouble(Collection<Double> array) {
        double sum = 0;
        for (Double value : array) {
            sum += value;
        }
        return sum;
    }

    public static double sum(Collection<Float> array) {
        double sum = 0;
        for (float value : array) {
            sum += value;
        }
        return sum;
    }

    public static Double sum(Double[] array) {
        Double sum = 0D;
        for (Double value : array) {
            sum += value;
        }
        return sum;
    }

    /**
     * 统计一个数组中的每一位数字,在该数组中小于等于自己的数还有几个
     * 例如数组3,2,1,1. 经过本函数后得到的结果是4,3,2,2
     * 入参array必须是降序排序后的数组
     */
    public static int[] countNumPositives(Double[] array) {
        int step = 0;
        int n = array.length;
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            while (step < n && array[i].equals(array[step])) {
                result[step] = n - i;
                step++;
            }
        }
        return result;
    }

    public static Result<Double[]> lagrangeInterpolation(Double[] x, Double[] y) {
        int n = x.length;
        Double[] res = new Double[n];
        if (n == y.length) {
            Double result;
            for (int i = 0; i < n; i++) {
                result = (double) 0;
                for (int j = 0; j < n - 2; j++) {
                    result += (x[i] - x[j + 1]) * (x[i] - x[j + 2]) / ((x[j] - x[j + 1]) * (x[j] - x[j + 2]));
                }
                result += (x[i] - x[n - 3]) * (x[i] - x[n - 1]) / ((x[n - 2] - x[n - 3]) * (x[n - 2] - x[n - 1]));
                result += (x[i] - x[n - 3]) * (x[i] - x[n - 2]) / ((x[n - 1] - x[n - 3]) * (x[n - 1] - x[n - 2]));
                res[i] = result;
            }
            return Result.OK(res);
        } else {
            return Result.Error("Interpolation Error.");
        }
    }

    /**
     * Get numCutOffs points equally picked from [a,b).
     */
    public static Double[] linspace(Double a, Double b, int numCutOffs) {
        Double[] result = new Double[numCutOffs];
        double inc = Math.abs(b - a) / (numCutOffs - 1);
        for (int i = 0; i < numCutOffs; i++) {
            result[i] = a + inc * i;
        }
        return result;
    }

    /**
     * Get the row-mean of rows in array[].
     */
    public static Double[] getRowMean(Double[][] array) {
        int arrayLength = array.length;
        int arrayWidth = array[0].length;
        Double[] rowMean = new Double[arrayWidth];
        double sumRowElement = 0;
        for (int i = 0; i < arrayWidth; i++) {
            for (int j = 0; j < arrayLength; j++) {
                sumRowElement += array[j][i];
            }
            rowMean[i] = sumRowElement / arrayLength;
            sumRowElement = 0;
        }
        return rowMean;
    }

    /**
     * Return index of nearest elements of samplePoints[] in array[].
     */
    public static Integer[] findNearestMatches(Double[] array, Double[] samplePoints, int useSortOrder) {

        int numBasis = array.length;
        int numSamples = samplePoints.length;
        Integer[] results = new Integer[numSamples];
        int i, bestJ;
        int low, mid, high;
        double spI, bestDist, dist;
        int sortOrder;

        if (useSortOrder != 1) {
            for (i = 0; i < numSamples; i++) {
                spI = samplePoints[i];
                bestJ = 0;
                bestDist = Math.abs(array[0] - spI);
                for (int j = 1; j < numBasis; j++) {
                    dist = Math.abs(array[j] - spI);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestJ = j;
                    }
                }
                results[i] = bestJ;

            }
            return results;
        }
        sortOrder = ArrayUtil.findSortOrder(array);
        for (i = 0; i < numSamples; i++) {
            spI = samplePoints[i];
            if (sortOrder == 0) {
                bestJ = 0;
                bestDist = Math.abs(array[0] - spI);
                for (int j = 1; j < numBasis; j++) {
                    dist = Math.abs(array[j] - spI);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestJ = j;
                    }
                }
            } else if (sortOrder == 1) {
                low = 0;
                high = numBasis - 1;
                bestJ = -1;
                if (array[low] == spI) {
                    bestJ = low;
                } else if (array[high] == spI) {
                    bestJ = high;
                } else {
                    while (low < high - 1) {
                        mid = (low + high) / 2;
                        if (array[mid] == spI) {
                            bestJ = mid;
                        }
                        if (array[mid] < spI) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    if (bestJ == -1) {
                        if (Math.abs(array[low] - spI) < Math.abs(array[high] - spI)) {
                            bestJ = low;
                        } else {
                            bestJ = high;
                        }
                    }
                }
                while (bestJ > 0) {
                    if (array[bestJ - 1].equals(array[bestJ])) {
                        bestJ = bestJ - 1;
                    } else {
                        break;
                    }
                }
            } else {
                low = 0;
                high = numBasis - 1;
                bestJ = -1;
                if (array[low] == spI) {
                    bestJ = low;
                } else if (array[high] == spI) {
                    bestJ = high;
                } else {
                    while (low < high - 1) {
                        mid = (low + high) / 2;
                        if (array[mid] == spI) {
                            bestJ = mid;
                            break;
                        }
                        if (array[mid] > spI) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    if (bestJ == -1) {
                        if (Math.abs(array[low] - spI) < Math.abs(array[high] - spI)) {
                            bestJ = low;
                        } else {
                            bestJ = high;
                        }
                    }
                }
                while (bestJ > 0) {
                    if (array[bestJ - 1].equals(array[bestJ])) {
                        bestJ = bestJ - 1;
                    } else {
                        break;
                    }
                }
            }
            results[i] = bestJ;

        }
        return results;
    }

    /**
     * Get an array of Max in the rest.
     */
    public static double[] cumMax(double[] array) {
        double max = array[0];
        int length = array.length;
        double[] result = new double[length];
        for (int i = 0; i < length; i++) {
            if (array[i] > max) {
                max = array[i];
            }
            result[i] = max;
        }
        return result;
    }

    /**
     * Find index of the min value.
     */
    public static int argmin(double[] array) {
        double min = array[0];
        int minIndex = 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i] < min) {
                min = array[i];
                minIndex = i;
            }
        }
        return minIndex;
    }

    /**
     * Count number of values bigger than threshold in array.
     */
    public static int countOverThreshold(Double[] array, double threshold) {
        int n = 0;
        for (Double i : array) {
            if (i >= threshold) {
                n++;
            }
        }
        return n;
    }

    public static Double[] average(Double[][] array) {
        Double[] averagedW = new Double[array[0].length];
        double sum = 0.0;
        for (int i = 0; i < array[0].length; i++) {
            for (Double[] j : array) {
                sum += j[i];
            }
            averagedW[i] = sum / array.length;
            sum = 0;
        }
        return averagedW;
    }

    /**
     * 对一个Double类型保留小数点后的尾数
     *
     * @param d          原double值
     * @param keepLength 10^要保留的位数,比如要保留两位,就传入100
     * @return
     */
    public static double keepLength(double d, int keepLength) {
        return ((double) Math.round(d * keepLength) / keepLength);
    }

    public static double corr(Double[] x, Double[] y) {
        if (x == null || y == null || x.length != y.length) {
            return Double.NaN;
        }
        double sx = 0.0, sy = 0.0, sx2 = 0.0, sy2 = 0.0, sxy = 0.0;
        double n = x.length;
        for (int i = 0; i < x.length; i++) {
            double xi = x[i], yi = y[i];
            sx += xi;
            sy += yi;
            sx2 += Math.sqrt(xi);
            sy2 += Math.sqrt(yi);
            sxy += xi * yi;
        }
        return (n * sxy - sx * sy) / Math.sqrt(Math.max(0.000001, (n * sx2 - Math.sqrt(sx)) * (n * sy2 - Math.sqrt(sy))));
    }
}
