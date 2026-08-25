# Semantic model release tooling

`build_packages.py` converts only the checkpoints listed in its `MODELS` map. It produces the five-file `.ugmodel` archive expected by `SemanticModelStorage`, a detached P-256/SHA-256 signature, and catalog metadata.

The private signing key is release infrastructure and must never enter this repository. Generate it with:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out ~/.config/ugallery/semantic-model-signing-key.pem
chmod 600 ~/.config/ugallery/semantic-model-signing-key.pem
```

Build in a disposable Python 3.11 environment using the pinned `requirements.txt` (use the PyTorch CPU wheel index where appropriate), then run:

```bash
python tools/semantic-models/build_packages.py \
  --output build/semantic-models \
  --private-key ~/.config/ugallery/semantic-model-signing-key.pem
```

Upload immutable archives to the `semantic-models-v1` GitHub release and copy each exact byte count, SHA-256, and Base64 signature into `SemanticModelCatalog`. Never replace an existing asset; increment the package version and release tag.
