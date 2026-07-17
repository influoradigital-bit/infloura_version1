'use client';

import * as React from 'react';
import { TimelineEvent } from '@/lib/types';
import { MessageEventCard } from './event-cards/message-card';
import { ProposalEventCard } from './event-cards/proposal-card';
import { ContractEventCard } from './event-cards/contract-card';
import { DeliverableEventCard } from './event-cards/deliverable-card';
import { PaymentEventCard } from './event-cards/payment-card';
import { SystemEventCard } from './event-cards/system-card';

export function TimelineEventCard({
  event,
  currentUserType,
}: {
  event: TimelineEvent;
  currentUserType: 'brand' | 'creator';
}) {
  // Route to correct component based on tag
  switch (event.tag) {
    case 'message':
      return <MessageEventCard event={event} currentUserType={currentUserType} />;
    case 'proposal':
      return <ProposalEventCard event={event} currentUserType={currentUserType} />;
    case 'contract':
      return <ContractEventCard event={event} currentUserType={currentUserType} />;
    case 'deliverable':
      return <DeliverableEventCard event={event} currentUserType={currentUserType} />;
    case 'payment':
      return <PaymentEventCard event={event} />;
    case 'system':
      return <SystemEventCard event={event} />;
    default:
      return null;
  }
}
