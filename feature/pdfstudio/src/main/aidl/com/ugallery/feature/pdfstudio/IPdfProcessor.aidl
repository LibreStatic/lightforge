package com.ugallery.feature.pdfstudio;
import android.os.ParcelFileDescriptor;
import com.ugallery.feature.pdfstudio.IPdfProgress;
interface IPdfProcessor {
    String inspect(in ParcelFileDescriptor source);
    String preview(in ParcelFileDescriptor source, int page, int width, in ParcelFileDescriptor output);
    String exportPdf(String jobId, in ParcelFileDescriptor manifest, boolean compact, in List<ParcelFileDescriptor> sources, in ParcelFileDescriptor output, IPdfProgress progress);
    void cancel(String jobId);
}
