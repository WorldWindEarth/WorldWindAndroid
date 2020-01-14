/*
 * Copyright (c) 2016 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration. All Rights Reserved.
 */

package gov.nasa.worldwind.render;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.util.Logger;
import gov.nasa.worldwind.util.Retriever;
import gov.nasa.worldwind.util.WWUtil;

public class ImageRetriever extends Retriever<ImageSource, ImageOptions, Bitmap> {

    protected Resources resources;

    public ImageRetriever(int maxSimultaneousRetrievals) {
        super(maxSimultaneousRetrievals);
    }

    public Resources getResources() {
        return resources;
    }

    public void setResources(Resources res) {
        this.resources = res;
    }

    @Override
    protected void retrieveAsync(ImageSource imageSource, ImageOptions imageOptions,
                                 Callback<ImageSource, ImageOptions, Bitmap> callback) {
        try {
            Bitmap bitmap = this.decodeImage(imageSource, imageOptions);

            if (bitmap != null) {
                // Apply bitmap transformation if required
                ImageSource.Transformer transformer = imageSource.getTransformer();
                if (transformer != null) {
                    bitmap = transformer.transform(bitmap);
                }

                callback.retrievalSucceeded(this, imageSource, imageOptions, bitmap);
            } else {
                callback.retrievalFailed(this, imageSource, null); // failed but no exception
            }
        } catch (Throwable logged) {
            callback.retrievalFailed(this, imageSource, logged); // failed with exception
        }
    }

    // TODO can we explicitly recycle bitmaps from image sources other than direct Bitmap references?
    // TODO does explicit recycling help?
    protected Bitmap decodeImage(ImageSource imageSource, ImageOptions imageOptions) throws IOException {
        if (imageSource.isBitmap()) {
            return imageSource.asBitmap();
        }

        if (imageSource.isBitmapFactory()) {
            return imageSource.asBitmapFactory().createBitmap();
        }

        if (imageSource.isResource()) {
            return this.decodeResource(imageSource.asResource(), imageOptions);
        }

        if (imageSource.isFilePath()) {
            return this.decodeFilePath(imageSource.asFilePath(), imageOptions);
        }

        if (imageSource.isUrl()) {
            return this.decodeUrl(imageSource.asUrl(), imageOptions);
        }

        return this.decodeUnrecognized(imageSource);
    }

    protected Bitmap decodeResource(int id, ImageOptions imageOptions) {
        BitmapFactory.Options factoryOptions = this.bitmapFactoryOptions(imageOptions);
        return (this.resources != null) ? BitmapFactory.decodeResource(this.resources, id, factoryOptions) : null;
    }

    protected Bitmap decodeFilePath(String pathName, ImageOptions imageOptions) {
        BitmapFactory.Options factoryOptions = this.bitmapFactoryOptions(imageOptions);
        return BitmapFactory.decodeFile(pathName, factoryOptions);
    }

    protected Bitmap decodeUrl(String urlString, ImageOptions imageOptions) throws IOException {
        // TODO retry absent resources, they are currently handled but suppressed entirely after the first failure
        // TODO configurable connect and read timeouts

        Bitmap cached = attemptGlobalCacheSourceResolution(urlString);
        if (cached != null) {
            return cached;
        }

        InputStream stream = null;
        try {
            URLConnection conn = new URL(urlString).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(30000);

            stream = new BufferedInputStream(conn.getInputStream());

            BitmapFactory.Options factoryOptions = this.bitmapFactoryOptions(imageOptions);
            Bitmap fetchedBmp = BitmapFactory.decodeStream(stream, null, factoryOptions);
            if (fetchedBmp != null) {
                addToGlobalCache(urlString, fetchedBmp);
            }
            return fetchedBmp;
        } finally {
            WWUtil.closeSilently(stream);
        }
    }

    protected Bitmap attemptGlobalCacheSourceResolution(String urlString) {
        // At the moment the cache directory is global for all ImageRetrievers in a given JVM.
        // Minor // TODO may be to add a configuration object to this class for setting per-retriever cache directories.
        String cacheDir = System.getProperty("worldwind.ImageRetriever.decodeUrlCacheDir");
        if (cacheDir == null) {
            return null; // Calling app did not set a cache dir
        }
        File cacheDirFD = new File(cacheDir);
        if (!cacheDirFD.exists()) {
            try {
                cacheDirFD.mkdirs();
            }
            catch (SecurityException sx) {
                Logger.logMessage(Logger.ERROR, "ImageRetriever", "attemptGlobalCacheSourceResolution", "Cannot create cache directory");
                return null; // Coult not create cache dir
            }
        }

        File cacheFileFD = getGlobalCacheUrlFile(urlString);
        if (!cacheFileFD.exists()) {
            return null;
        }
        long fileSizeInBytes = cacheFileFD.length();
        if (fileSizeInBytes < 1) {
            return null; // File exists, but is empty.
        }
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeStream(new FileInputStream(cacheFileFD));
        }
        catch (FileNotFoundException fnfex) {
            Logger.logMessage(Logger.ERROR, "ImageRetriever", "attemptGlobalCacheSourceResolution", "Cannot decode cache file into Bitmap");
        }
        return bmp;
    }

    protected void addToGlobalCache(String urlString, Bitmap bitmap) {
        File cacheFileFD = getGlobalCacheUrlFile(urlString);
        try (FileOutputStream out = new FileOutputStream(cacheFileFD)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (IOException e) {
            Logger.logMessage(Logger.ERROR, "ImageRetriever", "addToGlobalCache", "Cannot write bitmap to cache file");
        }
    }

    /**
     * This performs concatination of {@code System.getProperty("worldwind.ImageRetriever.decodeUrlCacheDir")}
     * with a SHA-256 hex string of {@code urlString}, returning {@code null} if
     * anything does not exist ({@code System.getProperty("worldwind.ImageRetriever.decodeUrlCacheDir") == null} etc)
     * 
     * This will NOT create a cache directory, and instead returns null when the directory path stored in
     * {@code System.getProperty("worldwind.ImageRetriever.decodeUrlCacheDir")} does not exist.
     *
     * This has been made public and static to as to be used elsewhere; this likely deserves a class
     * of it's own as it performs a caching operation which is not unique to ImageRetriever.
     * 
     * @param urlString The url which produces the cached file's name
     * @param fileNameFormatStr A format string which takes a single %s argument used to create the cache file name.
     * @return a file object pointing to the cached file (file may not exist)
     */
    public static File getGlobalCacheUrlFile(String urlString, String fileNameFormatStr) {
        String cacheDir = System.getProperty("worldwind.ImageRetriever.decodeUrlCacheDir");
        if (cacheDir == null) {
            return null;
        }
        File cacheDirFD = new File(cacheDir);
        if (!cacheDirFD.exists()) {
            return null;
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException nsax) {
            Logger.logMessage(Logger.ERROR, "ImageRetriever", "getGlobalCacheUrlFile", "No MessageDigest instance available for \"SHA-256\"");
            return null; // No sha-256 hash implementation for us to use
        }

        md.update(urlString.getBytes());

        byte[] digest = md.digest();
        String digestStr = decodeTohexStr(digest).toUpperCase();

        // At the moment all cached bitmaps are stored as .png files
        String cacheFilename = String.format(fileNameFormatStr, digestStr);
        File cacheFileFD = new File(cacheDirFD, cacheFilename);

        return cacheFileFD;
    }
    public static File getGlobalCacheUrlFile(String urlString) {
        return getGlobalCacheUrlFile(urlString, "%s.png");
    }

    private static String decodeTohexStr(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    protected Bitmap decodeUnrecognized(ImageSource imageSource) {
        Logger.log(Logger.WARN, "Unrecognized image source \'" + imageSource + "\'");
        return null;
    }

    protected BitmapFactory.Options bitmapFactoryOptions(ImageOptions imageOptions) {
        BitmapFactory.Options factoryOptions = new BitmapFactory.Options();
        factoryOptions.inScaled = false; // suppress default image scaling; load the image in its native dimensions

        if (imageOptions != null) {
            switch (imageOptions.imageConfig) {
                case WorldWind.RGBA_8888:
                    factoryOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    break;
                case WorldWind.RGB_565:
                    factoryOptions.inPreferredConfig = Bitmap.Config.RGB_565;
                    break;
            }
        }

        return factoryOptions;
    }
}
