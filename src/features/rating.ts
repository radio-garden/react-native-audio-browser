export interface HeartRating {
  hasHeart: boolean
}

export interface ThumbsRating {
  isThumbsUp: boolean
}

export interface StarRating {
  stars: number
}

export interface PercentageRating {
  percentage: number
}

export type Rating = HeartRating | ThumbsRating | StarRating | PercentageRating
