ALTER TABLE workspaces
  ADD COLUMN kyc_gstin_doc_url VARCHAR(500) NULL AFTER pan,
  ADD COLUMN kyc_pan_doc_url VARCHAR(500) NULL AFTER kyc_gstin_doc_url;
