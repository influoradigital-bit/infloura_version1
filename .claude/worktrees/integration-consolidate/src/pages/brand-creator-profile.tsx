import * as React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  CheckCircle2,
  MapPin,
  Mail,
  Heart,
  Bookmark,
  BookmarkCheck,
  ExternalLink,
  Users,
  Star,
  Play,
  Eye,
  MessageCircle,
  Clock,
  Instagram,
  Youtube,
  Twitter,
  Sparkles,
  TrendingUp,
  Calendar,
  Globe,
  Languages,
  Briefcase,
  IndianRupee,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';

// Mock Creator Data - Indian Context
const mockCreator = {
  id: '1',
  displayName: 'Priya Sharma',
  username: '@priyacreates',
  bio: 'Fashion & lifestyle creator from Mumbai. Passionate about sustainable fashion, travel, and authentic storytelling. Let us create something beautiful together.',
  avatarUrl: null,
  location: 'Mumbai, Maharashtra',
  languages: ['Hindi', 'English', 'Marathi'],
  email: 'priya@priyacreates.com',
  website: 'priyacreates.com',
  isVerified: true,
  isAvailable: true,
  joinedDate: 'March 2020',
  categories: ['Fashion', 'Lifestyle', 'Beauty', 'Travel'],
  
  stats: {
    totalFollowers: 892000,
    avgEngagement: 5.2,
    avgLikes: 46500,
    avgComments: 1820,
    avgViews: 245000,
    completedCampaigns: 67,
    rating: 4.9,
    reviewCount: 52,
  },

  platforms: [
    {
      name: 'Instagram',
      icon: Instagram,
      handle: '@priyacreates',
      followers: 485000,
      engagement: 5.8,
      verified: true,
      color: '#E4405F',
    },
    {
      name: 'YouTube',
      icon: Youtube,
      handle: 'Priya Creates',
      followers: 312000,
      engagement: 4.2,
      verified: true,
      color: '#FF0000',
    },
    {
      name: 'Twitter',
      icon: Twitter,
      handle: '@priyacreates',
      followers: 95000,
      engagement: 3.1,
      verified: false,
      color: '#1DA1F2',
    },
  ],

  audience: {
    ageGroups: [
      { range: '18-24', percentage: 32 },
      { range: '25-34', percentage: 45 },
      { range: '35-44', percentage: 18 },
      { range: '45+', percentage: 5 },
    ],
    gender: { female: 68, male: 30, other: 2 },
    topCities: [
      { city: 'Mumbai', percentage: 22 },
      { city: 'Delhi', percentage: 18 },
      { city: 'Bangalore', percentage: 15 },
      { city: 'Hyderabad', percentage: 12 },
      { city: 'Pune', percentage: 8 },
    ],
    interests: ['Fashion', 'Beauty', 'Travel', 'Fitness', 'Food'],
    authenticity: 96,
  },

  portfolio: [
    { id: 1, type: 'Reel', brand: 'Myntra', views: 1250000, likes: 89000 },
    { id: 2, type: 'Post', brand: 'Nykaa', views: 450000, likes: 32000 },
    { id: 3, type: 'Video', brand: 'Boat', views: 890000, likes: 45000 },
    { id: 4, type: 'Reel', brand: 'Sugar Cosmetics', views: 2100000, likes: 156000 },
    { id: 5, type: 'Post', brand: 'FabIndia', views: 320000, likes: 28000 },
    { id: 6, type: 'Story', brand: 'Mamaearth', views: 280000, likes: 21000 },
  ],

  rates: {
    instagram: [
      { type: 'Single Post', price: 45000 },
      { type: 'Reel', price: 75000 },
      { type: 'Story (3 frames)', price: 15000 },
      { type: 'Post + Reel Bundle', price: 100000 },
    ],
    youtube: [
      { type: 'Integration (60-90s)', price: 150000 },
      { type: 'Dedicated Video', price: 300000 },
    ],
  },

  pastBrands: ['Myntra', 'Nykaa', 'Boat', 'Sugar Cosmetics', 'FabIndia', 'Mamaearth', 'Puma India', 'H&M India'],

  reviews: [
    { brand: 'Myntra', rating: 5, comment: 'Exceptional content quality and professionalism. Delivered ahead of schedule.', date: '2024-04' },
    { brand: 'Nykaa', rating: 5, comment: 'Great engagement on the campaign. Would definitely work again.', date: '2024-03' },
    { brand: 'Boat', rating: 4, comment: 'Creative approach and good communication throughout.', date: '2024-02' },
  ],

  metrics: {
    responseTime: '< 4 hours',
    completionRate: 98,
    onTimeDelivery: 96,
    repeatClients: 72,
  },
};

// Format helpers
const formatNumber = (n: number): string => {
  if (n >= 10000000) return `${(n / 10000000).toFixed(1)}Cr`;
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return n.toString();
};

const formatINR = (n: number): string => {
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(0)}K`;
  return n.toLocaleString('en-IN');
};

export default function BrandCreatorProfilePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const creator = mockCreator;

  const [isSaved, setIsSaved] = React.useState(false);
  const [isInviteOpen, setIsInviteOpen] = React.useState(false);
  const [selectedCampaign, setSelectedCampaign] = React.useState('');
  const [inviteMessage, setInviteMessage] = React.useState('');
  const [isSubmitting, setIsSubmitting] = React.useState(false);

  const mockCampaigns = [
    { id: 'c1', name: 'Summer Fashion 2024' },
    { id: 'c2', name: 'Festive Collection Launch' },
    { id: 'c3', name: 'New Product Reveal' },
    { id: 'new', name: '+ Create New Campaign' },
  ];

  const handleInvite = async () => {
    if (selectedCampaign === 'new') {
      navigate('/brand/campaigns/new');
      return;
    }
    setIsSubmitting(true);
    await new Promise((r) => setTimeout(r, 1500));
    setIsSubmitting(false);
    setIsInviteOpen(false);
    setSelectedCampaign('');
    setInviteMessage('');
  };

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex h-14 items-center gap-4 px-4 md:px-6">
          <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div className="flex-1" />
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setIsSaved(!isSaved)}
            className={isSaved ? 'text-primary' : ''}
          >
            {isSaved ? <BookmarkCheck className="h-4 w-4" /> : <Bookmark className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      <div className="mx-auto max-w-5xl px-4 py-6 md:px-6 md:py-8">
        {/* Profile Header */}
        <div className="flex flex-col gap-6 md:flex-row md:items-start md:gap-8">
          {/* Avatar */}
          <Avatar className="h-24 w-24 md:h-32 md:w-32 ring-2 ring-border">
            <AvatarImage src={creator.avatarUrl || undefined} />
            <AvatarFallback className="bg-muted text-2xl md:text-3xl font-medium">
              {creator.displayName.split(' ').map(n => n[0]).join('')}
            </AvatarFallback>
          </Avatar>

          {/* Info */}
          <div className="flex-1 space-y-4">
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-2xl font-semibold tracking-tight">{creator.displayName}</h1>
                {creator.isVerified && (
                  <CheckCircle2 className="h-5 w-5 fill-primary text-primary-foreground" />
                )}
              </div>
              <p className="text-muted-foreground">{creator.username}</p>
            </div>

            <p className="text-sm text-muted-foreground leading-relaxed max-w-2xl">
              {creator.bio}
            </p>

            {/* Meta */}
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <MapPin className="h-3.5 w-3.5" />
                {creator.location}
              </span>
              <span className="flex items-center gap-1.5">
                <Languages className="h-3.5 w-3.5" />
                {creator.languages.join(', ')}
              </span>
              <span className="flex items-center gap-1.5">
                <Calendar className="h-3.5 w-3.5" />
                Joined {creator.joinedDate}
              </span>
              {creator.isAvailable && (
                <Badge variant="secondary" className="bg-green-500/10 text-stage-approved-fg border-0">
                  <span className="mr-1.5 h-1.5 w-1.5 rounded-full bg-green-500 animate-pulse" />
                  Available
                </Badge>
              )}
            </div>

            {/* Categories */}
            <div className="flex flex-wrap gap-1.5">
              {creator.categories.map((cat) => (
                <Badge key={cat} variant="secondary" className="font-normal">
                  {cat}
                </Badge>
              ))}
            </div>

            {/* Actions */}
            <div className="flex flex-wrap gap-3 pt-2">
              <Button onClick={() => setIsInviteOpen(true)}>
                <Sparkles className="mr-2 h-4 w-4" />
                Invite to Campaign
              </Button>
              <Button
                variant="outline"
                onClick={() => navigate(`/brand/messages?creator=${creator.id}`)}
              >
                <Mail className="mr-2 h-4 w-4" />
                Message
              </Button>
              <Button variant="outline" asChild>
                <a href={`https://${creator.website}`} target="_blank" rel="noopener noreferrer">
                  <Globe className="mr-2 h-4 w-4" />
                  Website
                </a>
              </Button>
            </div>
          </div>
        </div>

        {/* Stats Grid */}
        <div className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
          {[
            { label: 'Followers', value: formatNumber(creator.stats.totalFollowers), icon: Users },
            { label: 'Engagement', value: `${creator.stats.avgEngagement}%`, icon: TrendingUp },
            { label: 'Avg Likes', value: formatNumber(creator.stats.avgLikes), icon: Heart },
            { label: 'Avg Views', value: formatNumber(creator.stats.avgViews), icon: Eye },
            { label: 'Campaigns', value: creator.stats.completedCampaigns.toString(), icon: Briefcase },
            { label: 'Rating', value: creator.stats.rating.toString(), icon: Star },
          ].map((stat) => (
            <div
              key={stat.label}
              className="rounded-lg border bg-card p-4 text-center"
            >
              <stat.icon className="mx-auto mb-2 h-4 w-4 text-muted-foreground" />
              <p className="text-lg font-semibold">{stat.value}</p>
              <p className="text-xs text-muted-foreground">{stat.label}</p>
            </div>
          ))}
        </div>

        {/* Platforms */}
        <div className="mt-8">
          <h2 className="mb-4 text-sm font-medium text-muted-foreground uppercase tracking-wider">
            Platforms
          </h2>
          <div className="grid gap-3 sm:grid-cols-3">
            {creator.platforms.map((platform) => (
              <div
                key={platform.name}
                className="flex items-center gap-4 rounded-lg border bg-card p-4"
              >
                <div
                  className="flex h-10 w-10 items-center justify-center rounded-full"
                  style={{ backgroundColor: `${platform.color}15` }}
                >
                  <platform.icon className="h-5 w-5" style={{ color: platform.color }} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5">
                    <p className="font-medium truncate">{platform.name}</p>
                    {platform.verified && (
                      <CheckCircle2 className="h-3.5 w-3.5 fill-primary text-primary-foreground flex-shrink-0" />
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground">{platform.handle}</p>
                </div>
                <div className="text-right">
                  <p className="font-semibold">{formatNumber(platform.followers)}</p>
                  <p className="text-xs text-muted-foreground">{platform.engagement}% eng</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <Separator className="my-8" />

        {/* Tabs */}
        <Tabs defaultValue="overview" className="space-y-6">
          <TabsList className="w-full justify-start h-auto p-1 bg-muted/50">
            <TabsTrigger value="overview" className="text-sm">Overview</TabsTrigger>
            <TabsTrigger value="audience" className="text-sm">Audience</TabsTrigger>
            <TabsTrigger value="portfolio" className="text-sm">Portfolio</TabsTrigger>
            <TabsTrigger value="rates" className="text-sm">Rates</TabsTrigger>
            <TabsTrigger value="reviews" className="text-sm">Reviews</TabsTrigger>
          </TabsList>

          {/* Overview Tab */}
          <TabsContent value="overview" className="space-y-6">
            {/* Work Metrics */}
            <div className="grid gap-4 sm:grid-cols-4">
              {[
                { label: 'Response Time', value: creator.metrics.responseTime },
                { label: 'Completion Rate', value: `${creator.metrics.completionRate}%` },
                { label: 'On-Time Delivery', value: `${creator.metrics.onTimeDelivery}%` },
                { label: 'Repeat Clients', value: `${creator.metrics.repeatClients}%` },
              ].map((metric) => (
                <div key={metric.label} className="rounded-lg border p-4">
                  <p className="text-2xl font-semibold">{metric.value}</p>
                  <p className="text-sm text-muted-foreground">{metric.label}</p>
                </div>
              ))}
            </div>

            {/* Past Brands */}
            <div>
              <h3 className="mb-3 text-sm font-medium text-muted-foreground uppercase tracking-wider">
                Brands Worked With
              </h3>
              <div className="flex flex-wrap gap-2">
                {creator.pastBrands.map((brand) => (
                  <Badge key={brand} variant="outline" className="font-normal">
                    {brand}
                  </Badge>
                ))}
              </div>
            </div>
          </TabsContent>

          {/* Audience Tab */}
          <TabsContent value="audience" className="space-y-6">
            <div className="grid gap-6 md:grid-cols-2">
              {/* Age Distribution */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Age Distribution</h3>
                <div className="space-y-3">
                  {creator.audience.ageGroups.map((group) => (
                    <div key={group.range} className="space-y-1.5">
                      <div className="flex justify-between text-sm">
                        <span>{group.range}</span>
                        <span className="text-muted-foreground">{group.percentage}%</span>
                      </div>
                      <Progress value={group.percentage} className="h-2" />
                    </div>
                  ))}
                </div>
              </div>

              {/* Gender */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Gender Split</h3>
                <div className="flex h-4 overflow-hidden rounded-full">
                  <div
                    className="bg-pink-500"
                    style={{ width: `${creator.audience.gender.female}%` }}
                  />
                  <div
                    className="bg-blue-500"
                    style={{ width: `${creator.audience.gender.male}%` }}
                  />
                  <div
                    className="bg-purple-500"
                    style={{ width: `${creator.audience.gender.other}%` }}
                  />
                </div>
                <div className="mt-3 flex gap-4 text-sm">
                  <span className="flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-full bg-pink-500" />
                    Female {creator.audience.gender.female}%
                  </span>
                  <span className="flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-full bg-blue-500" />
                    Male {creator.audience.gender.male}%
                  </span>
                </div>
              </div>

              {/* Top Cities */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Top Cities</h3>
                <div className="space-y-3">
                  {creator.audience.topCities.map((city, i) => (
                    <div key={city.city} className="flex items-center gap-3">
                      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-muted text-xs font-medium">
                        {i + 1}
                      </span>
                      <span className="flex-1">{city.city}</span>
                      <span className="text-muted-foreground">{city.percentage}%</span>
                    </div>
                  ))}
                </div>
              </div>

              {/* Authenticity */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Audience Authenticity</h3>
                <div className="flex items-center gap-4">
                  <div className="relative h-24 w-24">
                    <svg className="h-24 w-24 -rotate-90" viewBox="0 0 36 36">
                      <circle cx="18" cy="18" r="16" fill="none" stroke="currentColor" strokeWidth="2" className="text-muted" />
                      <circle
                        cx="18" cy="18" r="16" fill="none" stroke="currentColor" strokeWidth="2"
                        strokeDasharray={`${creator.audience.authenticity} 100`}
                        className="text-green-500"
                      />
                    </svg>
                    <div className="absolute inset-0 flex items-center justify-center">
                      <span className="text-xl font-semibold">{creator.audience.authenticity}%</span>
                    </div>
                  </div>
                  <div className="text-sm text-muted-foreground">
                    <p>Real followers verified</p>
                    <p className="mt-1 text-stage-approved-fg font-medium">Excellent authenticity</p>
                  </div>
                </div>
              </div>
            </div>
          </TabsContent>

          {/* Portfolio Tab */}
          <TabsContent value="portfolio">
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {creator.portfolio.map((item) => (
                <div
                  key={item.id}
                  className="group relative aspect-square overflow-hidden rounded-lg bg-muted"
                >
                  <div className="absolute inset-0 flex items-center justify-center">
                    {item.type === 'Reel' || item.type === 'Video' ? (
                      <Play className="h-12 w-12 text-muted-foreground/50" />
                    ) : (
                      <Eye className="h-12 w-12 text-muted-foreground/50" />
                    )}
                  </div>
                  <div className="absolute inset-0 flex flex-col justify-end bg-gradient-to-t from-black/80 via-black/20 to-transparent p-4 opacity-0 transition-opacity group-hover:opacity-100">
                    <Badge variant="secondary" className="w-fit mb-2">{item.type}</Badge>
                    <p className="text-sm font-medium text-white">for {item.brand}</p>
                    <div className="mt-2 flex gap-4 text-xs text-white/80">
                      <span className="flex items-center gap-1">
                        <Eye className="h-3 w-3" />
                        {formatNumber(item.views)}
                      </span>
                      <span className="flex items-center gap-1">
                        <Heart className="h-3 w-3" />
                        {formatNumber(item.likes)}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </TabsContent>

          {/* Rates Tab */}
          <TabsContent value="rates" className="space-y-6">
            {/* Instagram Rates */}
            <div className="rounded-lg border p-5">
              <div className="mb-4 flex items-center gap-2">
                <Instagram className="h-5 w-5 text-[#E4405F]" />
                <h3 className="font-medium">Instagram</h3>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {creator.rates.instagram.map((rate) => (
                  <div
                    key={rate.type}
                    className="flex items-center justify-between rounded-lg bg-muted/50 p-4"
                  >
                    <span>{rate.type}</span>
                    <span className="font-semibold flex items-center gap-1">
                      <IndianRupee className="h-3.5 w-3.5" />
                      {formatINR(rate.price)}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* YouTube Rates */}
            <div className="rounded-lg border p-5">
              <div className="mb-4 flex items-center gap-2">
                <Youtube className="h-5 w-5 text-[#FF0000]" />
                <h3 className="font-medium">YouTube</h3>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {creator.rates.youtube.map((rate) => (
                  <div
                    key={rate.type}
                    className="flex items-center justify-between rounded-lg bg-muted/50 p-4"
                  >
                    <span>{rate.type}</span>
                    <span className="font-semibold flex items-center gap-1">
                      <IndianRupee className="h-3.5 w-3.5" />
                      {formatINR(rate.price)}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <p className="text-sm text-muted-foreground">
              * Rates are indicative and may vary based on campaign requirements, exclusivity, and usage rights.
            </p>
          </TabsContent>

          {/* Reviews Tab */}
          <TabsContent value="reviews" className="space-y-4">
            {/* Rating Summary */}
            <div className="flex items-center gap-6 rounded-lg border p-5">
              <div className="text-center">
                <p className="text-4xl font-semibold">{creator.stats.rating}</p>
                <div className="mt-1 flex gap-0.5">
                  {Array.from({ length: 5 }).map((_, i) => (
                    <Star
                      key={i}
                      className={cn(
                        'h-4 w-4',
                        i < Math.floor(creator.stats.rating)
                          ? 'fill-yellow-400 text-yellow-400'
                          : 'fill-muted text-muted'
                      )}
                    />
                  ))}
                </div>
                <p className="mt-1 text-sm text-muted-foreground">
                  {creator.stats.reviewCount} reviews
                </p>
              </div>
              <Separator orientation="vertical" className="h-16" />
              <div className="text-sm text-muted-foreground">
                <p>Based on verified brand collaborations</p>
                <p className="mt-1">All reviews are from completed campaigns</p>
              </div>
            </div>

            {/* Review List */}
            <div className="space-y-4">
              {creator.reviews.map((review, i) => (
                <div key={i} className="rounded-lg border p-5">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-muted font-medium">
                        {review.brand.charAt(0)}
                      </div>
                      <div>
                        <p className="font-medium">{review.brand}</p>
                        <p className="text-sm text-muted-foreground">{review.date}</p>
                      </div>
                    </div>
                    <div className="flex gap-0.5">
                      {Array.from({ length: 5 }).map((_, i) => (
                        <Star
                          key={i}
                          className={cn(
                            'h-4 w-4',
                            i < review.rating
                              ? 'fill-yellow-400 text-yellow-400'
                              : 'fill-muted text-muted'
                          )}
                        />
                      ))}
                    </div>
                  </div>
                  <p className="mt-3 text-sm text-muted-foreground leading-relaxed">
                    {review.comment}
                  </p>
                </div>
              ))}
            </div>
          </TabsContent>
        </Tabs>
      </div>

      {/* Invite Dialog */}
      <Dialog open={isInviteOpen} onOpenChange={setIsInviteOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Invite {creator.displayName}</DialogTitle>
            <DialogDescription>
              Send a collaboration invitation for one of your campaigns.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label>Select Campaign</Label>
              <Select value={selectedCampaign} onValueChange={setSelectedCampaign}>
                <SelectTrigger>
                  <SelectValue placeholder="Choose a campaign" />
                </SelectTrigger>
                <SelectContent>
                  {mockCampaigns.map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {selectedCampaign && selectedCampaign !== 'new' && (
              <div className="space-y-2">
                <Label>Message (optional)</Label>
                <Textarea
                  placeholder="Add a personal message..."
                  value={inviteMessage}
                  onChange={(e) => setInviteMessage(e.target.value)}
                  rows={3}
                />
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIsInviteOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleInvite} disabled={!selectedCampaign || isSubmitting}>
              {isSubmitting ? 'Sending...' : selectedCampaign === 'new' ? 'Create Campaign' : 'Send Invite'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
