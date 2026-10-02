#!/bin/sh
# 로컬 S3 버킷 + 브라우저 직접 업로드용 CORS
awslocal s3 mb s3://ttok-local
awslocal s3api put-bucket-cors --bucket ttok-local --cors-configuration '{"CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["PUT","GET"],"AllowedHeaders":["*"],"MaxAgeSeconds":3000}]}'
