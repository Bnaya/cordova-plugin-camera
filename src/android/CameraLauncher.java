/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.camera;

import java.lang.Math;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Bitmap.CompressFormat;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

/**
 * This class launches the camera view, allows the user to take a picture, closes the camera view,
 * and returns the captured image.  When the camera view is closed, the screen displayed before
 * the camera view was shown is redisplayed.
 */
public class CameraLauncher extends CordovaPlugin implements MediaScannerConnectionClient {

    private static final int DATA_URL = 0;              // Return base64 encoded string
    private static final int FILE_URI = 1;              // Return file uri (content://media/external/images/media/2 for Android)
    private static final int NATIVE_URI = 2;            // On Android, this is the same as FILE_URI

    private static final int PHOTOLIBRARY = 0;          // Choose image from picture library (same as SAVEDPHOTOALBUM for Android)
    private static final int CAMERA = 1;                // Take picture from camera
    private static final int SAVEDPHOTOALBUM = 2;       // Choose image from picture library (same as PHOTOLIBRARY for Android)

    private static final int PICTURE = 0;               // allow selection of still pictures only. DEFAULT. Will return format specified via DestinationType
    private static final int VIDEO = 1;                 // allow selection of video only, ONLY RETURNS URL
    private static final int ALLMEDIA = 2;              // allow selection from all media types

    private static final int JPEG = 0;                  // Take a picture of type JPEG
    private static final int PNG = 1;                   // Take a picture of type PNG
    private static final String GET_PICTURE = "Get Picture";
    private static final String GET_VIDEO = "Get Video";
    private static final String GET_All = "Get All";

    private static final String LOG_TAG = "CameraLauncher";

    private int mQuality;                   // Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
    private int targetWidth;                // desired width of the image
    private int targetHeight;               // desired height of the image
    private Uri imageUri;                   // Uri of captured image
    private int encodingType;               // Type of encoding to use
    private int mediaType;                  // What type of media to retrieve
    private boolean saveToPhotoAlbum;       // Should the picture be saved to the device's photo album
    private boolean correctOrientation;     // Should the pictures orientation be corrected
    //private boolean allowEdit;              // Should we allow the user to crop the image. UNUSED.

    public CallbackContext callbackContext;
    private int numPics;

    private MediaScannerConnection conn;    // Used to update gallery app with newly-written files
    private Uri scanMe;                     // Uri of image to be added to content store

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  A PluginResult object with a status and message.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        if (action.equals("takePicture")) {

            // Defaults
            int srcType = CAMERA;
            int destType = FILE_URI;
            this.saveToPhotoAlbum = false;
            this.targetHeight = 0;
            this.targetWidth = 0;
            this.encodingType = JPEG;
            this.mediaType = PICTURE;
            this.mQuality = 80;

            // Set values from arguments
            this.mQuality = args.getInt(0);
            destType = args.getInt(1);
            srcType = args.getInt(2);

            this.targetWidth = Math.min(Math.max(args.getInt(3), -1), -1);
            this.targetHeight = Math.min(Math.max(args.getInt(4), -1), -1);

            this.encodingType = args.getInt(5);
            this.mediaType = args.getInt(6);

            //this.allowEdit = args.getBoolean(7); // This field is unused.

            this.correctOrientation = args.getBoolean(8);
            this.saveToPhotoAlbum = args.getBoolean(9);

            try {
                if (srcType == CAMERA) {
                    this.takePicture(destType, encodingType);
                }
                else if ((srcType == PHOTOLIBRARY) || (srcType == SAVEDPHOTOALBUM)) {
                    this.getImage(srcType, destType);
                }
            }
            catch (IllegalArgumentException e)
            {
                callbackContext.error("Illegal Argument Exception");
                PluginResult r = new PluginResult(PluginResult.Status.ERROR);
                callbackContext.sendPluginResult(r);
                return true;
            }

            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);

            return true;
        }
        return false;
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    private String getTempDirectoryPath() {
        File cache = null;

        // SD Card Mounted
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            cache = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/Android/data/" + cordova.getActivity().getPackageName() + "/cache/");
        }
        // Use internal storage
        else {
            cache = cordova.getActivity().getCacheDir();
        }

        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    /**
     * Take a picture with the camera.
     * When an image is captured or the camera view is cancelled, the result is returned
     * in CordovaActivity.onActivityResult, which forwards the result to this.onActivityResult.
     *
     * The image can either be returned as a base64 string or a URI that points to the file.
     * To display base64 string in an img tag, set the source to:
     *      img.src="data:image/jpeg;base64,"+result;
     * or to display URI in an img tag
     *      img.src=result;
     *
     * @param quality           Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     * @param returnType        Set the type of image to return.
     */
    public void takePicture(int returnType, int encodingType) {
        // Save the number of images currently on disk for later
        this.numPics = queryImgDB(whichContentStore()).getCount();

        // Display camera
        Intent intent = new Intent("android.media.action.IMAGE_CAPTURE");

        // Specify file so that large image is captured and returned
        File photo = createCaptureFile(encodingType);
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.fromFile(photo));
        this.imageUri = Uri.fromFile(photo);

        if (this.cordova != null) {
            this.cordova.startActivityForResult((CordovaPlugin) this, intent, (CAMERA + 1) * 16 + returnType + 1);
        }
//        else
//            LOG.d(LOG_TAG, "ERROR: You must use the CordovaInterface for this to work correctly. Please implement it in your activity");
    }

    /**
     * Create a file in the applications temporary directory based upon the supplied encoding.
     *
     * @param encodingType of the image to be taken
     * @return a File object pointing to the temporary picture
     */
    private File createCaptureFile(int encodingType) {
        File photo = null;
        if (encodingType == JPEG) {
            photo = new File(this.getTempDirectoryPath(), ".Pic.jpg");
        } else if (encodingType == PNG) {
            photo = new File(this.getTempDirectoryPath(), ".Pic.png");
        } else {
            throw new IllegalArgumentException("Invalid Encoding Type: " + encodingType);
        }
        return photo;
    }

    /**
     * Get image from photo library.
     *
     * @param quality           Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     * @param srcType           The album to get image from.
     * @param returnType        Set the type of image to return.
     */
    // TODO: Images selected from SDCARD don't display correctly, but from CAMERA ALBUM do!
    public void getImage(int srcType, int returnType) {
        Intent intent = new Intent();
        String title = GET_PICTURE;
        if (this.mediaType == PICTURE) {
            intent.setType("image/*");
        }
        else if (this.mediaType == VIDEO) {
            intent.setType("video/*");
            title = GET_VIDEO;
        }
        else if (this.mediaType == ALLMEDIA) {
            // I wanted to make the type 'image/*, video/*' but this does not work on all versions
            // of android so I had to go with the wildcard search.
            intent.setType("*/*");
            title = GET_All;
        }

        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (this.cordova != null) {
            this.cordova.startActivityForResult((CordovaPlugin) this, Intent.createChooser(intent,
                    new String(title)), (srcType + 1) * 16 + returnType + 1);
        }
    }

    /**
     * Called when the camera view exits.
     *
     * @param requestCode       The request code originally supplied to startActivityForResult(),
     *                          allowing you to identify who this result came from.
     * @param resultCode        The integer result code returned by the child activity through its setResult().
     * @param intent            An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        // Get src and dest types from request code
        int srcType = (requestCode / 16) - 1;
        int destType = (requestCode % 16) - 1;

        boolean exifAvailable = false;
        ExifHelper exif = new ExifHelper();
        Bitmap originBitmap;
        String originPath;
        Uri originUri;
        String originMimeType;

        Bitmap targetBitmap;
        ByteArrayOutputStream outputData = new ByteArrayOutputStream();
        Uri targetUri;
        String targetPath;
        String targetMimeType = this.encodingType == JPEG ? "image/jpeg" : "image/png";


        if (resultCode != Activity.RESULT_OK) {
            if (resultCode == Activity.RESULT_CANCELED) {
                this.invokeErrorCallback("cancelled by user");
            }
            else {
                this.invokeErrorCallback("Did not complete!");
            }

            return;
        }

        if (srcType != CAMERA && srcType != PHOTOLIBRARY && srcType != SAVEDPHOTOALBUM) {
            this.invokeErrorCallback("unexpected source type");
            return;
        }


        // If you ask for video or all media type you will automatically get back a file URI
        // and there will be no attempt to resize any returned data
        if ((srcType == PHOTOLIBRARY || srcType == SAVEDPHOTOALBUM) && this.mediaType != PICTURE) {
            this.invokeSuccessCallback(intent.getData().toString());

            return;
        }

        if (srcType == CAMERA) {
            if (this.encodingType == JPEG) {
                originPath = this.getTempDirectoryPath() + "/.Pic.jpg";
                originMimeType = "image/jpeg";
            } else {
                originPath = this.getTempDirectoryPath() + "/.Pic.png";
                originMimeType = "image/png";
            }

            originUri = this.imageUri;
        }
        // PHOTOLIBRARY or SAVEDPHOTOALBUM that same on android
        else {
            originUri = intent.getData();

            // Get mime type
            originMimeType = FileHelper.getMimeType(originUri.toString(), this.cordova).toLowerCase();

            originPath = FileHelper.getRealPath(originUri, this.cordova);

            // Some content: URIs do not map to file paths (android 4.4 library, picasa).
            // And we must have real path for the exif helper so lets make one!
            // Its a temp file, so the extension ain't matter
            if (originPath == null) {
                originPath = this.getTempDirectoryPath() + "/.tempOriginImage";

                try {
                    FileOutputStream tos = new FileOutputStream(originPath);
                    InputStream ios = FileHelper.getInputStreamFromUriString(originUri.toString(), this.cordova);

                    FileHelper.copy(ios, tos);

                    ios.close();
                    tos.close();
                } catch (IOException e) {
                    this.invokeErrorCallback("Unexpected IO error");
                    return;
                }
            }
        }

        // Read exif data if applicable
        if (originMimeType == "image/jpeg") {
            try {
                exif.createInFile(originPath);
                exif.readExifData();
                exifAvailable = true;
            } catch (Exception e) {
                this.invokeErrorCallback("Failed to read exif:" + e.getMessage());
                return;
            }   
        }

        // Generate target uri & path
        targetUri = Uri.fromFile(
            new File(
                this.getTempDirectoryPath(), 
                System.currentTimeMillis() + "." + (targetMimeType == "image/jpeg" ? "jpg" : "png")
            )
        );
        targetPath = FileHelper.getRealPath(targetUri, this.cordova);

        // Will contain the final result to be sent back
        String result;

        // Check if we can send the image back as is or we need to do some processing 
        if (
            // no need to change format
            targetMimeType == originMimeType &&
            // no need to rescale
            this.targetHeight == -1 && this.targetWidth == -1 &&
            // No need to use diffrent jpeg quality level
            (this.mQuality == 100 && targetMimeType == "image/jpeg") &&
            // No need to try and fix orientation
            (!this.correctOrientation || 
            // or no need to rotate the photo
            (exifAvailable && exif.getOrientation() == 0))
        ) {
            if (destType == DATA_URL) {
                byte[] fileContent;
                // Dafuck that should do ?? :P
                // checkForDuplicateImage(DATA_URL);

                // Read the raw file data
                try {
                    fileContent = FileHelper.toByteArray(originUri);
                } catch (IOException e) {
                    this.invokeErrorCallback("Unexpected IO error");

                    return;
                }

                // Encode as base64 and return the result to the js
                result = new String(
                        Base64.encode(
                            fileContent,
                            Base64.NO_WRAP
                        )
                    );
            }
            // Return file path
            else {
                result = "file://" + originPath;
            }
        }
        // We need to rescale/rotate/convert format
        else {
            // Read the image file to bitmap
            // If the image is smaller then the targetWidth & targetHeight it won't be scaled, just retunred as bitmap
            try {
                originBitmap = this.getScaledBitmap(originPath, this.targetWidth, this.targetHeight);
            } catch (IOException e) {
                this.invokeErrorCallback("Unexpected IO error");
                return;
            }

            // Correct orientation if applicable
            if (exifAvailable && this.correctOrientation && exif.getOrientation() != 0) {
                targetBitmap = this.levelBitmap(originBitmap, exif.getOrientation());
                exif.resetOrientation();
            } else {
                targetBitmap = originBitmap;
            }

            // prepare the output
            targetBitmap.compress(
                targetMimeType == "image/jpeg" ? CompressFormat.JPEG : CompressFormat.PNG,
                this.mQuality, outputData
            );

            // As base 64
            // Note that we can't restore exif data when using base 64 
            if (destType == DATA_URL) {
                result = new String(
                    Base64.encode(
                        outputData.toByteArray(),
                        Base64.NO_WRAP
                    )
                );
            }
            // Return file path
            else {
                // Save data to file system
                try {
                    FileOutputStream tos = new FileOutputStream(targetPath);
                    tos.write(outputData.toByteArray());
                } catch (Exception e) {
                    this.invokeErrorCallback("Unexpected IO error");

                    return;
                }

                // Restore exif if applicable
                if (exifAvailable && targetMimeType == "image/jpeg") {
                    try {
                        exif.createOutFile(targetPath);
                        exif.writeExifData();
                    } catch (IOException e) {
                        this.invokeErrorCallback("Unexpected IO error");

                        return;
                    }
                }

                result = "file://" + targetPath;
            }
        }

        this.invokeSuccessCallback(result);
    }

    /**
     * Figure out if the bitmap should be rotated. For instance if the picture was taken in
     * portrait mode
     *
     * @param rotate
     * @param bitmap
     * @return rotated bitmap
     */
    private Bitmap levelBitmap(Bitmap bitmap, int rotate) {
        Matrix matrix = new Matrix();
        if (rotate == 180) {
            matrix.setRotate(rotate);
        } else {
            matrix.setRotate(rotate, (float) bitmap.getWidth() / 2, (float) bitmap.getHeight() / 2);
        }

        try
        {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        catch (OutOfMemoryError oom)
        {
            // You can run out of memory if the image is very large:
            // http://simonmacdonald.blogspot.ca/2012/07/change-to-camera-code-in-phonegap-190.html
            // If this happens, simply do not rotate the image and return it unmodified.
            // If you do not catch the OutOfMemoryError, the Android app crashes.
        }

        return bitmap;
    }

    /**
     * Return a scaled bitmap based on the target width and height
     *
     * @param imagePath
     * @return
     * @throws IOException
     */
    private Bitmap getScaledBitmap(String imageUrl, int maxWidth, int maxHeight) throws IOException {
        // If no new width or height were specified return the original bitmap
        if (maxWidth <= 0 && maxHeight <= 0) {
            return BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(imageUrl, cordova));
        }

        // figure out the original width and height of the image
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(imageUrl, cordova), null, options);

        //CB-2292: WTF? Why is the width null?
        if(options.outWidth == 0 || options.outHeight == 0)
        {
            return null;
        }

        // determine the correct aspect ratio
        int[] widthHeight = this.calculateAspectRatio(options.outWidth, options.outHeight);

        // Load in the smallest bitmap possible that is closest to the size we want
        options.inJustDecodeBounds = false;
        options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight);
        Bitmap unscaledBitmap = BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(imageUrl, cordova), null, options);
        if (unscaledBitmap == null) {
            return null;
        }

        return Bitmap.createScaledBitmap(unscaledBitmap, widthHeight[0], widthHeight[1], true);
    }

    /**
     * Maintain the aspect ratio so the resulting image does not look smooshed
     *
     * @param origWidth
     * @param origHeight
     * @return
     */
    public int[] calculateAspectRatio(int origWidth, int origHeight) {
        int newWidth = this.targetWidth;
        int newHeight = this.targetHeight;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (newWidth * origHeight) / origWidth;
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (newHeight * origWidth) / origHeight;
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] retval = new int[2];
        retval[0] = newWidth;
        retval[1] = newHeight;
        return retval;
    }

    /**
     * Figure out what ratio we can load our image into memory at while still being bigger than
     * our desired width and height
     *
     * @param srcWidth
     * @param srcHeight
     * @param dstWidth
     * @param dstHeight
     * @return
     */
    public static int calculateSampleSize(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        final float srcAspect = (float)srcWidth / (float)srcHeight;
        final float dstAspect = (float)dstWidth / (float)dstHeight;

        if (srcAspect > dstAspect) {
            return srcWidth / dstWidth;
        } else {
            return srcHeight / dstHeight;
        }
      }

    /**
     * Creates a cursor that can be used to determine how many images we have.
     *
     * @return a cursor
     */
    private Cursor queryImgDB(Uri contentStore) {
        return this.cordova.getActivity().getContentResolver().query(
                contentStore,
                new String[] { MediaStore.Images.Media._ID },
                null,
                null,
                null);
    }

    /**
     * Cleans up after picture taking. Checking for duplicates and that kind of stuff.
     * @param newImage
     */
    private void cleanup(int imageType, Uri oldImage, Uri newImage, Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }

        // Clean up initial camera-written image file.
        (new File(FileHelper.stripFileProtocol(oldImage.toString()))).delete();

        checkForDuplicateImage(imageType);
        // Scan for the gallery to update pic refs in gallery
        if (this.saveToPhotoAlbum && newImage != null) {
            this.scanForGallery(newImage);
        }

        System.gc();
    }

    /**
     * Used to find out if we are in a situation where the Camera Intent adds to images
     * to the content store. If we are using a FILE_URI and the number of images in the DB
     * increases by 2 we have a duplicate, when using a DATA_URL the number is 1.
     *
     * @param type FILE_URI or DATA_URL
     */
    private void checkForDuplicateImage(int type) {
        int diff = 1;
        Uri contentStore = whichContentStore();
        Cursor cursor = queryImgDB(contentStore);
        int currentNumOfImages = cursor.getCount();

        if (type == FILE_URI && this.saveToPhotoAlbum) {
            diff = 2;
        }

        // delete the duplicate file if the difference is 2 for file URI or 1 for Data URL
        if ((currentNumOfImages - numPics) == diff) {
            cursor.moveToLast();
            int id = Integer.valueOf(cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media._ID)));
            if (diff == 2) {
                id--;
            }
            Uri uri = Uri.parse(contentStore + "/" + id);
            this.cordova.getActivity().getContentResolver().delete(uri, null, null);
            cursor.close();
        }
    }

    /**
     * Determine if we are storing the images in internal or external storage
     * @return Uri
     */
    private Uri whichContentStore() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else {
            return android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI;
        }
    }

    /**
     * Send error message to JavaScript.
     *
     * @param err
     */
    public void invokeErrorCallback(String err) {
        this.callbackContext.error(err);
    }

    public void invokeSuccessCallback(String result) {
        this.callbackContext.success(result);
    }

    private void scanForGallery(Uri newImage) {
        this.scanMe = newImage;
        if(this.conn != null) {
            this.conn.disconnect();
        }
        this.conn = new MediaScannerConnection(this.cordova.getActivity().getApplicationContext(), this);
        conn.connect();
    }

    public void onMediaScannerConnected() {
        try{
            this.conn.scanFile(this.scanMe.toString(), "image/*");
        } catch (java.lang.IllegalStateException e){
            LOG.e(LOG_TAG, "Can't scan file in MediaScanner after taking picture");
        }

    }

    public void onScanCompleted(String path, Uri uri) {
        this.conn.disconnect();
    }
}
