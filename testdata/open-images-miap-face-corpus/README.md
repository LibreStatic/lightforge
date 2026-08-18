# Open Images + MIAP face-detection corpus

This directory contains the reproducible manifest for the M4-T02 photographic face-detection
evaluation. The image files are intentionally excluded from Git and from every production source
set. Generate them locally with:

```bash
python3 tools/corpora/prepare_open_images_miap_face_corpus.py
```

The selector joins Open Images validation `Human face` boxes with the MIAP validation people
annotations. It produces 48 clean targets balanced across the available perceived-presentation
strata and 12 challenge targets (occluded or truncated). Images are resized to a 1024-pixel long
edge under `core/ml/src/androidTest/assets/images/` because that is the production detector's
maximum input size. Those generated images are ignored by Git and never enter a production source
set; the attribution manifest is tracked in both this directory and the androidTest assets.

## License and ethical scope

- Open Images annotations are licensed by Google LLC under CC BY 4.0.
- Images are listed by Open Images as CC BY 2.0. The generated manifest retains the original URL,
  landing page, author, title, and license URL for every selected image.
- MIAP adds annotations intended for fairness evaluation. Its perceived gender/age presentation
  labels are not identity, self-identified gender, or actual age. They must not be used to train or
  expose a demographic classifier.
- The upstream Open Images disclaimer says consumers should verify each image's license. Therefore
  the corpus is test-only, is never packaged in a release, and the manifest is the attribution and
  audit record.

Primary sources:

- https://storage.googleapis.com/openimages/web/factsfigures_v7.html
- https://storage.googleapis.com/openimages/web/extended.html#miap
- https://storage.googleapis.com/openimages/web/download_v7.html
