// KNOWN-GOOD 06 — F-0266 direction. This file is CORRECT, and its comments quote the forbidden
// shape verbatim while explaining why it was removed. A gate that reads bytes fails the very fix
// that closed the record; a gate that reads code does not.
//
//   const KanbanView = () => (
//     <div className="kanban" />
//   );
//   ...
//   {viewMode === 'kanban' && <KanbanView />}
//
// The above is the DEFECT, kept here only as documentation. Below is the fix.
import React from 'react';

export default function PipelinePage() {
  const [viewMode, setViewMode] = React.useState('kanban');

  /* Was: const BoardView = () => ( ... ) mounted as <BoardView />. Now a plain call. */
  const kanbanView = () => (
    <div className="kanban">
      {/* not <KanbanView /> any more — see the note at the top of this file */}
      <input defaultValue="" />
    </div>
  );

  return (
    <div>
      <button onClick={() => setViewMode('kanban')}>k</button>
      {viewMode === 'kanban' && kanbanView()}
    </div>
  );
}
