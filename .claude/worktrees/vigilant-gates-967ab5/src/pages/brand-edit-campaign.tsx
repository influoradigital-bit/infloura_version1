import { useParams } from 'react-router-dom';
import { CampaignForm } from '@/components/brand/campaigns/campaign-form';

export default function BrandEditCampaignPage() {
  const { id } = useParams<{ id: string }>();
  
  return <CampaignForm campaignId={id} />;
}
