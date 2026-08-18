// KNOWN-BAD 02 — the F-0335 injection itself: the SAME defect class under an identifier
// the old gate never enumerated. This is the fixture that fails a name-locked gate.
import React from 'react';

export default function BrandPipelinePage() {
  const [viewMode, setViewMode] = React.useState<'board' | 'kanban'>('board');

  const boardView = () => <div className="board" />;

  const KanbanView = () => (
    <div className="kanban">
      <input defaultValue="" />
    </div>
  );

  return (
    <div>
      {viewMode === 'board' && boardView()}
      {viewMode === 'kanban' && <KanbanView />}
    </div>
  );
}
