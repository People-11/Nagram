/*
 * Copyright 2007 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData;
import com.google.zxing.qrcode.detector.Detector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This implementation can detect and decode QR Codes in an image.
 * Enhanced to better handle unclear or distorted QR codes.
 *
 * @author Sean Owen
 */
public class QRCodeReader implements Reader {

  private static final ResultPoint[] NO_POINTS = new ResultPoint[0];

  private final Decoder decoder = new Decoder();

  protected final Decoder getDecoder() {
    return decoder;
  }

  /**
   * Locates and decodes a QR code in an image.
   *
   * @return a String representing the content encoded by the QR code
   * @throws NotFoundException if a QR code cannot be found
   * @throws FormatException if a QR code cannot be decoded
   * @throws ChecksumException if error correction fails
   */
  @Override
  public Result decode(BinaryBitmap image) throws NotFoundException, ChecksumException, FormatException {
    return decode(image, null);
  }

  @Override
  public final Result decode(BinaryBitmap image, Map<DecodeHintType,?> hints)
      throws NotFoundException, ChecksumException, FormatException {
    
    // Create enhanced hints map
    Map<DecodeHintType, Object> enhancedHints = new HashMap<>();
    if (hints != null) {
      enhancedHints.putAll(hints);
    }
    
    // Always use TRY_HARDER for better results with unclear images
    if (!enhancedHints.containsKey(DecodeHintType.TRY_HARDER)) {
      enhancedHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }
    
    // First attempt with enhanced hints
    try {
      return decodeInternal(image, enhancedHints);
    } catch (NotFoundException | ChecksumException | FormatException e) {
      // Second attempt with pure barcode mode if not already tried
      if (!enhancedHints.containsKey(DecodeHintType.PURE_BARCODE)) {
        enhancedHints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        try {
          return decodeInternal(image, enhancedHints);
        } catch (Exception e2) {
          // Fall through to next attempt
        }
      }
      
      // Try again with the original error
      throw e;
    }
  }
  
  /**
   * Internal decode method with enhanced bit extraction
   */
  private Result decodeInternal(BinaryBitmap image, Map<DecodeHintType, ?> hints)
      throws NotFoundException, ChecksumException, FormatException {
    DecoderResult decoderResult;
    ResultPoint[] points;
    
    if (hints != null && hints.containsKey(DecodeHintType.PURE_BARCODE)) {
      BitMatrix bits = extractPureBits(image.getBlackMatrix());
      decoderResult = decoder.decode(bits, hints);
      points = NO_POINTS;
    } else {
      DetectorResult detectorResult = new Detector(image.getBlackMatrix()).detect(hints);
      decoderResult = decoder.decode(detectorResult.getBits(), hints);
      points = detectorResult.getPoints();
    }

    // If the code was mirrored: swap the bottom-left and the top-right points.
    if (decoderResult.getOther() instanceof QRCodeDecoderMetaData) {
      ((QRCodeDecoderMetaData) decoderResult.getOther()).applyMirroredCorrection(points);
    }

    Result result = new Result(decoderResult.getText(), decoderResult.getRawBytes(), points, BarcodeFormat.QR_CODE);
    List<byte[]> byteSegments = decoderResult.getByteSegments();
    if (byteSegments != null) {
      result.putMetadata(ResultMetadataType.BYTE_SEGMENTS, byteSegments);
    }
    String ecLevel = decoderResult.getECLevel();
    if (ecLevel != null) {
      result.putMetadata(ResultMetadataType.ERROR_CORRECTION_LEVEL, ecLevel);
    }
    if (decoderResult.hasStructuredAppend()) {
      result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE,
                         decoderResult.getStructuredAppendSequenceNumber());
      result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_PARITY,
                         decoderResult.getStructuredAppendParity());
    }
    return result;
  }

  @Override
  public void reset() {
    // do nothing
  }

  /**
   * Enhanced version of extractPureBits that is more tolerant of imperfections
   */
  private static BitMatrix extractPureBits(BitMatrix image) throws NotFoundException {
    int[] leftTopBlack = image.getTopLeftOnBit();
    int[] rightBottomBlack = image.getBottomRightOnBit();
    if (leftTopBlack == null || rightBottomBlack == null) {
      throw NotFoundException.getNotFoundInstance();
    }

    float moduleSize = estimateModuleSize(leftTopBlack, image);

    int top = leftTopBlack[1];
    int bottom = rightBottomBlack[1];
    int left = leftTopBlack[0];
    int right = rightBottomBlack[0];

    // Sanity check with more tolerance
    if (left >= right || top >= bottom) {
      // Try to adjust slightly for some tolerance
      int width = image.getWidth();
      int height = image.getHeight();
      
      if (left >= right) {
        // Try to find a better right boundary
        right = Math.min(width - 1, left + (int)(moduleSize * 21)); // QR code is at least 21 modules wide
      }
      
      if (top >= bottom) {
        // Try to find a better bottom boundary
        bottom = Math.min(height - 1, top + (int)(moduleSize * 21)); // QR code is at least 21 modules high
      }
      
      // If still invalid, throw exception
      if (left >= right || top >= bottom) {
        throw NotFoundException.getNotFoundInstance();
      }
    }

    // In some cases, the bottom/right is too far. Use the module size to make a better guess
    int matrixWidth = Math.round((right - left + 1) / moduleSize);
    int matrixHeight = Math.round((bottom - top + 1) / moduleSize);
    
    // Module size might be off, so make sure we have at least the minimum size
    if (matrixWidth <= 0 || matrixHeight <= 0) {
      throw NotFoundException.getNotFoundInstance();
    }
    
    // QR codes are square, but we'll allow some tolerance
    // If they're very different, use the smaller dimension
    if (Math.abs(matrixWidth - matrixHeight) > Math.min(matrixWidth, matrixHeight) / 5) {
      int dimension = Math.min(matrixWidth, matrixHeight);
      matrixWidth = dimension;
      matrixHeight = dimension;
    }

    // Push in the "border" by half the module width so that we start
    // sampling in the middle of the module. Just in case the image is a
    // little off, this will help recover.
    int nudge = (int) (moduleSize / 2.0f);
    top += nudge;
    left += nudge;

    // But careful that this does not sample off the edge
    int nudgedTooFarRight = left + (int) ((matrixWidth - 1) * moduleSize) - right;
    if (nudgedTooFarRight > 0) {
      if (nudgedTooFarRight > nudge) {
        // Neither way fits; abort
        throw NotFoundException.getNotFoundInstance();
      }
      left -= nudgedTooFarRight;
    }
    
    int nudgedTooFarDown = top + (int) ((matrixHeight - 1) * moduleSize) - bottom;
    if (nudgedTooFarDown > 0) {
      if (nudgedTooFarDown > nudge) {
        // Neither way fits; abort
        throw NotFoundException.getNotFoundInstance();
      }
      top -= nudgedTooFarDown;
    }

    // Now just read off the bits with adaptive sampling
    BitMatrix bits = new BitMatrix(matrixWidth, matrixHeight, 1);
    for (int y = 0; y < matrixHeight; y++) {
      int iOffset = top + (int) (y * moduleSize);
      for (int x = 0; x < matrixWidth; x++) {
        // Use adaptive sampling (check neighboring pixels)
        if (sampleGrid(image, left + (int) (x * moduleSize), iOffset, (int) moduleSize)) {
          bits.set(x, y);
        }
      }
    }
    return bits;
  }

  /**
   * Enhanced module size estimation that is more robust for unclear images
   */
  private static float estimateModuleSize(int[] leftTopBlack, BitMatrix image) throws NotFoundException {
    int height = image.getHeight();
    int width = image.getWidth();
    int x = leftTopBlack[0];
    int y = leftTopBlack[1];
    
    // Try to find module size by counting transitions along diagonal
    float diagonalSize = calculateModuleSizeFromDiagonal(x, y, width, height, image);
    
    // Try horizontal and vertical as well for better accuracy
    float horizontalSize = calculateModuleSizeOneWay(x, y, width, true, image);
    float verticalSize = calculateModuleSizeOneWay(x, y, height, false, image);
    
    // Use the most reasonable size
    float bestSize = diagonalSize;
    int validSizes = 1;
    
    if (horizontalSize > 0) {
      bestSize += horizontalSize;
      validSizes++;
    }
    
    if (verticalSize > 0) {
      bestSize += verticalSize;
      validSizes++;
    }
    
    return bestSize / validSizes;
  }
  
  /**
   * Calculate module size from diagonal (original method)
   */
  private static float calculateModuleSizeFromDiagonal(int startX, int startY, int width, int height, BitMatrix image) 
      throws NotFoundException {
    int x = startX;
    int y = startY;
    boolean inBlack = true;
    int transitions = 0;
    
    while (x < width && y < height) {
      if (inBlack != image.get(x, y)) {
        if (++transitions == 5) {
          break;
        }
        inBlack = !inBlack;
      }
      x++;
      y++;
    }
    
    if (x == width || y == height || transitions < 5) {
      // Ensure we go at least 5 transitions (black-white-black-white-black)
      throw NotFoundException.getNotFoundInstance();
    }
    
    return (x - startX) / 7.0f; // First 7 transitions are 1 module
  }
  
  /**
   * Calculate module size by going in one direction (horizontal or vertical)
   */
  private static float calculateModuleSizeOneWay(int startX, int startY, int maxDistance, 
                                                boolean horizontal, BitMatrix image) {
    int x = startX;
    int y = startY;
    boolean inBlack = true;
    int transitions = 0;
    
    while ((horizontal ? x : y) < maxDistance) {
      boolean isBlack = horizontal ? image.get(x, y) : image.get(y, x);
      
      if (inBlack != isBlack) {
        if (++transitions == 5) {
          break;
        }
        inBlack = !inBlack;
      }
      
      if (horizontal) {
        x++;
      } else {
        y++;
      }
    }
    
    if ((horizontal ? x : y) == maxDistance || transitions < 5) {
      // Couldn't find enough transitions
      return -1;
    }
    
    return (horizontal ? x - startX : y - startY) / 7.0f;
  }
  
  /**
   * Sample a grid point with some robustness to noise
   */
  private static boolean sampleGrid(BitMatrix image, int centerX, int centerY, int size) {
    // For very small modules, just use the center
    if (size <= 1) {
      return image.get(centerX, centerY);
    }
    
    // For larger modules, use a voting approach
    int blackPixels = 0;
    int totalPixels = 0;
    
    int sampleSize = Math.min(2, size / 2); // Don't sample too far from center
    
    for (int y = -sampleSize; y <= sampleSize; y++) {
      int sampleY = centerY + y;
      if (sampleY < 0 || sampleY >= image.getHeight()) {
        continue;
      }
      
      for (int x = -sampleSize; x <= sampleSize; x++) {
        int sampleX = centerX + x;
        if (sampleX < 0 || sampleX >= image.getWidth()) {
          continue;
        }
        
        if (image.get(sampleX, sampleY)) {
          blackPixels++;
        }
        totalPixels++;
      }
    }
    
    // Majority vote - if more than 40% are black, consider it black
    // This threshold is more aggressive than 50% to deal with faded QR codes
    return blackPixels * 100 >= totalPixels * 40;
  }
}