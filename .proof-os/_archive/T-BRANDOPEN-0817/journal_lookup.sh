#!/bin/bash
set -u
JOURNAL="C:/Users/Sage world/AppData/Roaming/Claude/local-agent-mode-sessions/3a613ffb-5d5c-4472-8b3e-6aa41ae4204d/b66976d0-d58d-4e93-84e6-9997f7df1500/rpm/plugin_01BnEF97nKc8pyi8gL7qpsSM/scripts/journal.py"
files=(
  "influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java"
  "influora-api/src/main/java/com/influora/domain/entity/Contract.java"
  "influora-api/src/main/java/com/influora/service/ContractService.java"
  "influora-api/src/main/java/com/influora/web/ContractController.java"
  "src/lib/api.ts"
  "src/components/brand/deal-room/deal-contract-tab.tsx"
  "influora-api/src/test/java/com/influora/service/ContractServiceTest.java"
  "influora-api/src/test/java/com/influora/service/ContractServiceDeliverableMaterializationTest.java"
  "influora-api/src/main/resources/db/migration/V20260817130000__contract_signer_names.sql"
  ".proof-os/gates/F-0292-signature-name-persisted.sh"
)
for f in "${files[@]}"; do
  python "$JOURNAL" add --who vikram --what read --file "$f" --task T-BRANDOPEN-0817 --stage lookup
done
echo DONE
