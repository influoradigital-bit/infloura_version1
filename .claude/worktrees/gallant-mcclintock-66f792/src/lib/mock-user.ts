import type { User } from './types';

export function createMockCreatorUser(overrides: Partial<User> = {}): User {
  const now = new Date();
  return {
    id: 'creator-1',
    email: 'priya@creator.com',
    userType: 'CREATOR',
    status: 'ACTIVE',
    emailVerified: true,
    phoneVerified: false,
    displayName: 'Priya Creator',
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}
