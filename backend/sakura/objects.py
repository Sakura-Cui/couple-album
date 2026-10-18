"""Private media storage. COS credentials remain on the server."""
import os
from pathlib import Path


class LocalObjects:
    remote = False

    def __init__(self, root):
        self.root = root

    def put(self, kind, key, source, mime):
        destination = self.root / kind / key
        if Path(source) != destination:
            Path(source).replace(destination)

    def delete(self, kind, key):
        (self.root / kind / key).unlink(missing_ok=True)

    def size(self, kind, key):
        return (self.root / kind / key).stat().st_size

    def read(self, kind, key, start=0, end=None):
        with (self.root / kind / key).open('rb') as file:
            file.seek(start)
            remaining = end - start + 1 if end is not None else self.size(kind, key) - start
            while remaining > 0:
                data = file.read(min(256 * 1024, remaining))
                if not data:
                    return
                remaining -= len(data)
                yield data


class CosObjects:
    remote = True

    def __init__(self, client=None, bucket=None, prefix=None):
        if client is None:
            from qcloud_cos import CosConfig, CosS3Client
            config = CosConfig(Region=os.environ['SAKURA_COS_REGION'],
                               SecretId=os.environ['SAKURA_COS_SECRET_ID'],
                               SecretKey=os.environ['SAKURA_COS_SECRET_KEY'],
                               Token=os.environ.get('SAKURA_COS_TOKEN') or None, Scheme='https', Timeout=60)
            client = CosS3Client(config)
        self.client = client
        self.bucket = bucket or os.environ['SAKURA_COS_BUCKET']
        self.region = os.environ.get('SAKURA_COS_REGION', '')
        self.prefix = (prefix or os.environ.get('SAKURA_COS_PREFIX', 'sakura-romance')).strip('/')
        if not self.prefix or '..' in self.prefix.split('/'):
            raise ValueError('COS prefix must be a non-empty application-specific path')

    def key(self, kind, key):
        return f'{self.prefix}/{kind}/{key}'

    def put(self, kind, key, source, mime):
        self.client.upload_file(Bucket=self.bucket, Key=self.key(kind, key), LocalFilePath=str(source),
                                PartSize=4, MAXThread=1, EnableMD5=True, ContentType=mime)

    def delete(self, kind, key):
        self.client.delete_object(Bucket=self.bucket, Key=self.key(kind, key))

    def copy_to(self, kind, key, target, target_kind, target_key):
        self.client.copy_object(Bucket=target.bucket, Key=target.key(target_kind, target_key),
                                CopySource={'Bucket': self.bucket, 'Region': self.region, 'Key': self.key(kind, key)})

    def size(self, kind, key):
        return int(self.client.head_object(Bucket=self.bucket, Key=self.key(kind, key))['Content-Length'])

    def read(self, kind, key, start=0, end=None):
        result = self.client.get_object(Bucket=self.bucket, Key=self.key(kind, key), Range=f'bytes={start}-{end if end is not None else ""}')
        stream = result['Body'].get_raw_stream()
        try:
            while True:
                piece = stream.read(256 * 1024)
                if not piece:
                    return
                yield piece
        finally:
            stream.close()
